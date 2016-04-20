/*
 * Copyright 2016 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.cats.redis.cluster;

import com.netflix.spinnaker.cats.agent.*;
import com.netflix.spinnaker.cats.module.CatsModuleAware;
import com.netflix.spinnaker.cats.redis.JedisSource;
import com.netflix.spinnaker.cats.thread.NamedThreadFactory;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;

import java.util.*;
import java.util.concurrent.*;

/*
 * The idea behind this scheduler is simple. Every agent it owns is always in one of two sorted sets,
 * WORKING, or WAITING. If it is in the WORKING set, it's being run, if it's in the WAITING set, it is waiting to run.
 * These sets are sorted by time, and the rank is time at which they are ready to run (in the WAITING set), or have
 * timed out (in the WORKING set). Therefore, if we do a `zrangebyscore SETNAME -inf CURRENT_TIME`, we get a list
 * of all agents that have expired. Those are then atomically pulled from the set, and run by the scheduler.
 *
 * The amortized cost of this scheduler is much lower than the original ClusteredAgentScheduler, since during each
 * cache interval every key will only be removed from Redis once. If the interval is 60s, and the agent polls every 1s,
 * we already have a (30s / 1) * (# of clouddrivers) factor of improvement.
 */
@SuppressFBWarnings
public class ClusteredSortAgentScheduler extends CatsModuleAware implements AgentScheduler, Runnable {
  private final JedisSource jedisSource;
  private final NodeStatusProvider nodeStatusProvider;
  private final AgentIntervalProvider intervalProvider;
  private final ExecutorService agentWorkPool;

  private static final Integer NOW = 0;
  private static final int REDIS_REFRESH_PERIOD = 30;
  private int runCount = 0;

  private final Logger log;

  private Map<String, AgentWorker> agents;
  private Optional<Semaphore> runningAgents;

  // This code assumes that every agent being run is in exactly either the WAITING or WORKING set.
  private static final String WAITING_SET = "WAITZ";
  private static final String WORKING_SET = "WORKZ";
  private static final String ADD_AGENT_SCRIPT = "addAgentScript";
  private static final String SWAP_SET_SCRIPT = "swapSetScript";
  private static final String CONDITIONAL_SWAP_SET_SCRIPT = "conditionalSwapSetScript";

  private ConcurrentHashMap<String, String> scriptShas;

  public ClusteredSortAgentScheduler(JedisSource jedisSource, NodeStatusProvider nodeStatusProvider, AgentIntervalProvider intervalProvider, Integer parallelism) {
    this.jedisSource = jedisSource;
    this.nodeStatusProvider = nodeStatusProvider;
    this.agents = new ConcurrentHashMap<>();
    this.intervalProvider = intervalProvider;
    this.log = LoggerFactory.getLogger(getClass());

    if (parallelism == 0 || parallelism < -1) {
      throw new IllegalArgumentException("Argument 'parallelism' must be positive, or -1 (for unlimited parallelism).");
    } else if (parallelism > 0) {
      this.runningAgents = Optional.of(new Semaphore(parallelism));
    } else {
      this.runningAgents = Optional.empty();
    }

    scriptShas = new ConcurrentHashMap<>();
    storeScripts();

    this.agentWorkPool = Executors.newCachedThreadPool(new NamedThreadFactory(AgentWorker.class.getSimpleName()));
    Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory(ClusteredSortAgentScheduler.class.getSimpleName()))
      .scheduleAtFixedRate(this, 0, 1, TimeUnit.SECONDS);
  }

  private void storeScripts() {
    try (Jedis jedis = jedisSource.getJedis()) {
      // When we switch an agent from one set to another, we first make sure it exists in the set we are removing it
      // from, and then we perform the swap. If this check fails, the thread performing the swap does not get ownership
      // of the agent.
      // Swap happens from KEYS[1] -> KEYS[2] with the agent type being ARGV[1], and the score being ARGV[2].
      scriptShas.put(SWAP_SET_SCRIPT, jedis.scriptLoad(
          "if redis.call('zrank', KEYS[1], ARGV[1]) ~= nil then\n" +
          "  redis.call('zrem', KEYS[1], ARGV[1])\n" +
          "  return redis.call('zadd', KEYS[2], ARGV[2], ARGV[1])\n" +
          "else return nil end\n"
      ));

      scriptShas.put(CONDITIONAL_SWAP_SET_SCRIPT, jedis.scriptLoad(
          "if redis.call('zrank', KEYS[1], ARGV[1]) == ARGV[3] then\n" +
          "  redis.call('zrem', KEYS[1], ARGV[1])\n" +
          "  return redis.call('zadd', KEYS[2], ARGV[2], ARGV[1])\n" +
          "else return nil end\n"
      ));

      // If the agent isn't present in either the WAITING or WORKING sets, it's safe to add. If it's present in either,
      // it's being worked on or was recently run, so leave it be.
      // KEYS[1] and KEYS[2] are checked for inclusion. If the agent is in neither ARGV[1] is added to KEYS[1] with score
      // ARGV[2].
      scriptShas.put(ADD_AGENT_SCRIPT, jedis.scriptLoad(
        "if redis.call('zrank', KEYS[1], ARGV[1]) ~= nil then\n" +
        "  if redis.call('zrank', KEYS[2], ARGV[1]) ~= nil then\n" +
        "    return redis.call('zadd', KEYS[1], ARGV[2], ARGV[1])\n" +
        "  else return nil end\n" +
        "else return nil end\n"));
    }
  }

  private String getScriptSha(String scriptName, Jedis jedis) {
    String scriptSha = scriptShas.get(scriptName);
    if (scriptSha == null) {
      storeScripts();
      scriptSha = scriptShas.get(scriptName);
      if (scriptSha == null) {
        throw new RuntimeException("Failed to load caching scripts.");
      }
    }

    if (!jedis.scriptExists(scriptSha)) {
      storeScripts();
    }

    return scriptShas.get(scriptName);
  }

  @Override
  public void schedule(Agent agent, AgentExecution agentExecution, ExecutionInstrumentation executionInstrumentation) {
    if (agent instanceof AgentSchedulerAware) {
      ((AgentSchedulerAware)agent).setAgentScheduler(this);
    }

    if (!(agentExecution instanceof CachingAgent.CacheExecution)) {
      throw new IllegalArgumentException("Sort scheduler requires agent executions to be of type CacheExecution");
    }

    agents.put(agent.getAgentType(), new AgentWorker(agent, (CachingAgent.CacheExecution)agentExecution, executionInstrumentation, this));
    try (Jedis jedis = jedisSource.getJedis()) {
      jedis.evalsha(getScriptSha(ADD_AGENT_SCRIPT, jedis), 2, WAITING_SET, WORKING_SET, agent.getAgentType(), score(NOW));
    }
  }

  @Override
  public void run() {
    if (!nodeStatusProvider.isNodeEnabled()) {
      return;
    }
    try {
      saturatePool();
    } catch (Throwable t) {
      log.error("Failed to run caching agents", t);
    } finally {
      runCount++;
    }
  }

  private static String score(long offset) {
    return String.format("%d", TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis()) + offset);
  }

  private String acquireAgent(Agent agent) {
    try (Jedis jedis = jedisSource.getJedis()) {
      String score = score(intervalProvider.getInterval(agent).getTimeout());
      if (jedis.evalsha(getScriptSha(SWAP_SET_SCRIPT, jedis),
          Arrays.asList(WAITING_SET, WORKING_SET),
          Arrays.asList(agent.getAgentType(), score)) != null) {
        return score;
      } else {
        return null;
      }
    }
  }

  private boolean conditionalReleaseAgent(Agent agent, String acquireScore) {
    try (Jedis jedis = jedisSource.getJedis()) {
      if (jedis.evalsha(getScriptSha(CONDITIONAL_SWAP_SET_SCRIPT, jedis),
          Arrays.asList(WORKING_SET, WAITING_SET),
          Arrays.asList(agent.getAgentType(), score(intervalProvider.getInterval(agent).getInterval()),
              acquireScore)) != null) {
        return true;
      } else {
        return false;
      }
    }
  }

  private boolean releaseAgent(Agent agent) {
    try (Jedis jedis = jedisSource.getJedis()) {
      if (jedis.evalsha(getScriptSha(SWAP_SET_SCRIPT, jedis),
          Arrays.asList(WORKING_SET, WAITING_SET),
          Arrays.asList(agent.getAgentType(), score(intervalProvider.getInterval(agent).getInterval()))) != null) {
        return true;
      } else {
        return false;
      }
    }
  }

  private void saturatePool() {
    try (Jedis jedis = jedisSource.getJedis()) {
      // Occasionally repopulate the agents in case redis went down. If they already exist, this is a NOOP
      if (runCount % REDIS_REFRESH_PERIOD == 0) {
        for (String agent : agents.keySet()) {
          jedis.evalsha(getScriptSha(ADD_AGENT_SCRIPT, jedis), 2, WAITING_SET, WORKING_SET, agent, score(NOW));
        }
      }

      // First cull threads in the WORKING set that have been there too long (TIMEOUT time).
      Set<String> oldKeys = jedis.zrangeByScore(WORKING_SET, "-inf", score(NOW));
      for (String key : oldKeys) {
        // Ignore result, since if this agent was released between now and the above jedis call, our work was done
        // for us.
        AgentWorker worker = agents.get(key);
        if (worker != null) {
          releaseAgent(worker.agent);
        }
      }

      // Now look for agents that have been in the queue for at least INTERVAL time.
      List<String> keys = new ArrayList<>();
      keys.addAll(jedis.zrangeByScore(WAITING_SET, "-inf", score(NOW)));
      Set<AgentWorker> workers = new HashSet<>();

      // Loop until we either run out of threads to use, or agents (which are keys) to run.
      while (!keys.isEmpty() && runningAgents.map(Semaphore::tryAcquire).orElse(true)) {
        String agent = keys.remove(0);

        AgentWorker worker = agents.get(agent);
        String score;
        if (worker != null && (score = acquireAgent(worker.agent)) != null) {
          // This score is used to determine if the worker thread running the agent is allowed to store its results.
          // If on release of this agent, the scores don't match, this agent was rescheduled by a separate thread.
          worker.setScore(score);
          workers.add(worker);
        }
      }

      for (AgentWorker worker : workers) {
        agentWorkPool.submit(worker);
      }
    }
  }

  private static class AgentWorker implements Runnable {
    private final Agent agent;
    private final CachingAgent.CacheExecution agentExecution;
    private final ExecutionInstrumentation executionInstrumentation;
    private final ClusteredSortAgentScheduler scheduler;
    private String acquireScore;

    AgentWorker(Agent agent, CachingAgent.CacheExecution agentExecution, ExecutionInstrumentation executionInstrumentation, ClusteredSortAgentScheduler scheduler) {
      this.agent = agent;
      this.agentExecution = agentExecution;
      this.executionInstrumentation = executionInstrumentation;
      this.scheduler = scheduler;
    }

    public void setScore(String score) {
      acquireScore = score;
    }

    @Override
    public void run() {
      assert acquireScore != null;
      CacheResult result = null;
      try {
        executionInstrumentation.executionStarted(agent);
        long startTime = System.nanoTime();
        result = agentExecution.executeAgentWithoutStore(agent);
        executionInstrumentation.executionCompleted(agent, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime));
      } catch (Throwable cause) {
        executionInstrumentation.executionFailed(agent, cause);
      } finally {
        // Regardless of success or failure, we need to try and release this agent. If the release is successful (we
        // own this agent), and a result was created, we can store it.
        scheduler.runningAgents.ifPresent(Semaphore::release);
        if (scheduler.conditionalReleaseAgent(agent, acquireScore) && result != null) {
          agentExecution.storeAgentResult(agent, result);
        }
      }
    }
  }

}