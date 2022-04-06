/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.flink.streaming.connectors.redis.common.container;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisSentinelPool;

import java.io.Closeable;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;

/**
 * Redis command container if we want to connect to a single Redis server or to Redis sentinels
 * If want to connect to a single Redis server, please use the first constructor {@link #RedisContainer(JedisPool)}.
 * If want to connect to a Redis sentinels, please use the second constructor {@link #RedisContainer(JedisSentinelPool)}
 */
public class RedisContainer implements RedisCommandsContainer, Closeable {

    private static final long serialVersionUID = 1L;

    private static final Logger LOG = LoggerFactory.getLogger(RedisContainer.class);

    private transient JedisPool jedisPool;
    private transient JedisSentinelPool jedisSentinelPool;

    private transient Map<String, Object> conf;

    private long batch = 0L;

    private int mod = -1;
    private int minMod = 50;

    Jedis pipelineJedis;

    /**
     * Use this constructor if to connect with single Redis server.
     *
     * @param jedisPool JedisPool which actually manages Jedis instances
     */
    public RedisContainer(JedisPool jedisPool) {
        Objects.requireNonNull(jedisPool, "Jedis Pool can not be null");
        this.jedisPool = jedisPool;
        this.jedisSentinelPool = null;
    }

    /**
     * Use this constructor if Redis environment is clustered with sentinels.
     *
     * @param sentinelPool SentinelPool which actually manages Jedis instances
     */
    public RedisContainer(final JedisSentinelPool sentinelPool) {
        Objects.requireNonNull(sentinelPool, "Jedis Sentinel Pool can not be null");
        this.jedisPool = null;
        this.jedisSentinelPool = sentinelPool;
    }

    /**
     * Closes the Jedis instances.
     */
    @Override
    public void close() throws IOException {
        if (pipelineJedis != null) {
            pipelineJedis.close();
            LOG.info("pipeline jedis closing...");
        }
        if (this.jedisPool != null) {
            this.jedisPool.close();
        }
        if (this.jedisSentinelPool != null) {
            this.jedisSentinelPool.close();
        }
    }

    @Override
    public void open() throws Exception {

        // echo() tries to open a connection and echos back the
        // message passed as argument. Here we use it to monitor
        // if we can communicate with the cluster.
        pipelineJedis = getInstance();
        pipelineJedis.echo("Test");
        LOG.info("jedis instance created.");
    }

    @Override
    public void setConf(Map<String, Object> conf) {
        this.conf = conf;
    }

    @Override
    public Map<String,Object> getConf() {
        return this.conf;
    }

    /**
     * Initialize jedis pipeline sync count number.
     *
     * @return
     */
    private int getMod() {
        if(conf == null){
            return minMod;
        }
        Object modVal = conf.get("redis.pipeline.sync.count");

        if (modVal == null || !(modVal instanceof Integer)) {
            mod = minMod;
            return mod;
        }
        try {
            int mv = (Integer) modVal;
            mod = mv > minMod ? mv : minMod;
        } catch (Exception e) {
            LOG.warn("Exception");
            mod = minMod;
        }
        LOG.info("Set pipeline sync count to {}.", mod);
        return mod;
    }

    @Override
    public void hset(final String key, final String hashField, final String value, final Integer ttl) {
        Jedis jedis = null;
        try {
            jedis = getInstance();
            jedis.hset(key, hashField, value);
            if (ttl != null) {
                jedis.expire(key, ttl);
            }
        } catch (Exception e) {
            if (LOG.isErrorEnabled()) {
                LOG.error("Cannot send Redis message with command HSET to key {} and hashField {} error message {}",
                        key, hashField, e.getMessage());
            }
            throw e;
        } finally {
            releaseInstance(jedis);
        }
    }

    @Override
    public void pipelinedHset(final String key, final String hashField, final String value) {
        pipelinedHsetEx(key,hashField,value,null);
    }


    @Override
    public void pipelinedHsetEx(final String key, final String hashField, final String value, final Integer ttl) {
        // if(mod != -1) init with -1.
        if (mod < minMod)
            mod = getMod();
        try {
            pipelineJedis.pipelined().hset(key, hashField, value);
            if (ttl != null) {
                pipelineJedis.pipelined().expire(key, ttl);
            }
            if (++batch % mod == 0) {
                pipelineJedis.pipelined().sync();
                LOG.debug("rnd:{}, mod:{}", batch / mod, mod);
            }
        } catch (Exception e) {
            if (LOG.isErrorEnabled()) {
                LOG.error("Cannot send Redis message with command HSET to key {} and hashField {} error message {} using pipeline",
                        key, hashField, e.getMessage());
            }
            throw e;
        } /*finally {
            releaseInstance(jedis);
        }*/
        // close pipelineJedis on close() method.
    }


    @Override
    public void hincrBy(final String key, final String hashField, final Long value, final Integer ttl) {
        Jedis jedis = null;
        try {
            jedis = getInstance();
            jedis.hincrBy(key, hashField, value);
            if (ttl != null) {
                jedis.expire(key, ttl);
            }
        } catch (Exception e) {
            if (LOG.isErrorEnabled()) {
                LOG.error("Cannot send Redis message with command HINCRBY to key {} and hashField {} error message {}",
                        key, hashField, e.getMessage());
            }
            throw e;
        } finally {
            releaseInstance(jedis);
        }
    }

    @Override
    public void rpush(final String listName, final String value) {
        Jedis jedis = null;
        try {
            jedis = getInstance();
            jedis.rpush(listName, value);
        } catch (Exception e) {
            if (LOG.isErrorEnabled()) {
                LOG.error("Cannot send Redis message with command RPUSH to list {} error message {}",
                        listName, e.getMessage());
            }
            throw e;
        } finally {
            releaseInstance(jedis);
        }
    }

    @Override
    public void lpush(String listName, String value) {
        Jedis jedis = null;
        try {
            jedis = getInstance();
            jedis.lpush(listName, value);
        } catch (Exception e) {
            if (LOG.isErrorEnabled()) {
                LOG.error("Cannot send Redis message with command LUSH to list {} error message {}",
                        listName, e.getMessage());
            }
            throw e;
        } finally {
            releaseInstance(jedis);
        }
    }

    @Override
    public void sadd(final String setName, final String value) {
        Jedis jedis = null;
        try {
            jedis = getInstance();
            jedis.sadd(setName, value);
        } catch (Exception e) {
            if (LOG.isErrorEnabled()) {
                LOG.error("Cannot send Redis message with command RPUSH to set {} error message {}",
                        setName, e.getMessage());
            }
            throw e;
        } finally {
            releaseInstance(jedis);
        }
    }

    @Override
    public void publish(final String channelName, final String message) {
        Jedis jedis = null;
        try {
            jedis = getInstance();
            jedis.publish(channelName, message);
        } catch (Exception e) {
            if (LOG.isErrorEnabled()) {
                LOG.error("Cannot send Redis message with command PUBLISH to channel {} error message {}",
                        channelName, e.getMessage());
            }
            throw e;
        } finally {
            releaseInstance(jedis);
        }
    }

    @Override
    public void set(final String key, final String value) {
        Jedis jedis = null;
        try {
            jedis = getInstance();
            jedis.set(key, value);
        } catch (Exception e) {
            if (LOG.isErrorEnabled()) {
                LOG.error("Cannot send Redis message with command SET to key {} error message {}",
                        key, e.getMessage());
            }
            throw e;
        } finally {
            releaseInstance(jedis);
        }
    }

    @Override
    public void setex(final String key, final String value, final Integer ttl) {
        Jedis jedis = null;
        try {
            jedis = getInstance();
            jedis.setex(key, ttl, value);
        } catch (Exception e) {
            if (LOG.isErrorEnabled()) {
                LOG.error("Cannot send Redis message with command SETEX to key {} error message {}",
                        key, e.getMessage());
            }
            throw e;
        } finally {
            releaseInstance(jedis);
        }
    }

    @Override
    public void pfadd(final String key, final String element) {
        Jedis jedis = null;
        try {
            jedis = getInstance();
            jedis.pfadd(key, element);
        } catch (Exception e) {
            if (LOG.isErrorEnabled()) {
                LOG.error("Cannot send Redis message with command PFADD to key {} error message {}",
                        key, e.getMessage());
            }
            throw e;
        } finally {
            releaseInstance(jedis);
        }
    }

    @Override
    public void zadd(final String key, final String score, final String element) {
        Jedis jedis = null;
        try {
            jedis = getInstance();
            jedis.zadd(key, Double.valueOf(score), element);
        } catch (Exception e) {
            if (LOG.isErrorEnabled()) {
                LOG.error("Cannot send Redis message with command ZADD to set {} error message {}",
                        key, e.getMessage());
            }
            throw e;
        } finally {
            releaseInstance(jedis);
        }
    }

    @Override
    public void zincrBy(final String key, final String score, final String element) {
        Jedis jedis = null;
        try {
            jedis = getInstance();
            jedis.zincrby(key, Double.valueOf(score), element);
        } catch (Exception e) {
            if (LOG.isErrorEnabled()) {
                LOG.error("Cannot send Redis message with command ZINCRBY to set {} error message {}",
                        key, e.getMessage());
            }
            throw e;
        } finally {
            releaseInstance(jedis);
        }
    }

    @Override
    public void zrem(final String key, final String element) {
        Jedis jedis = null;
        try {
            jedis = getInstance();
            jedis.zrem(key, element);
        } catch (Exception e) {
            if (LOG.isErrorEnabled()) {
                LOG.error("Cannot send Redis message with command ZREM to set {} error message {}",
                        key, e.getMessage());
            }
            throw e;
        } finally {
            releaseInstance(jedis);
        }
    }

    /**
     * Returns Jedis instance from the pool.
     *
     * @return the Jedis instance
     */
    private Jedis getInstance() {
        if (jedisSentinelPool != null) {
            return jedisSentinelPool.getResource();
        } else {
            return jedisPool.getResource();
        }
    }

    /**
     * Closes the jedis instance after finishing the command.
     *
     * @param jedis The jedis instance
     */
    private void releaseInstance(final Jedis jedis) {
        if (jedis == null) {
            return;
        }
        try {
            jedis.close();
        } catch (Exception e) {
            LOG.error("Failed to close (return) instance to pool", e);
        }
    }

    @Override
    public void incrByEx(String key, Long value, Integer ttl) {
        Jedis jedis = null;
        try {
            jedis = getInstance();
            jedis.incrBy(key, value);
            if (ttl != null) {
                jedis.expire(key, ttl);
            }
        } catch (Exception e) {
            if (LOG.isErrorEnabled()) {
                LOG.error("Cannot send Redis with incrby command to key {} with increment {}  with ttl {} error message {}",
                        key, value, ttl, e.getMessage());
            }
            throw e;
        } finally {
            releaseInstance(jedis);
        }
    }

    @Override
    public void decrByEx(String key, Long value, Integer ttl) {
        Jedis jedis = null;
        try {
            jedis = getInstance();
            jedis.decrBy(key, value);
            if (ttl != null) {
                jedis.expire(key, ttl);
            }
        } catch (Exception e) {
            if (LOG.isErrorEnabled()) {
                LOG.error("Cannot send Redis with decrBy command to key {} with decrement {}  with ttl {} error message {}",
                        key, value, ttl, e.getMessage());
            }
            throw e;
        } finally {
            releaseInstance(jedis);
        }
    }

    @Override
    public void incrBy(String key, Long value) {
        Jedis jedis = null;
        try {
            jedis = getInstance();
            jedis.incrBy(key, value);
        } catch (Exception e) {
            if (LOG.isErrorEnabled()) {
                LOG.error("Cannot send Redis with incrby command to key {} with increment {}  error message {}",
                        key, value, e.getMessage());
            }
            throw e;
        } finally {
            releaseInstance(jedis);
        }
    }

    @Override
    public void decrBy(String key, Long value) {
        Jedis jedis = null;
        try {
            jedis = getInstance();
            jedis.decrBy(key, value);
        } catch (Exception e) {
            if (LOG.isErrorEnabled()) {
                LOG.error("Cannot send Redis with decrBy command to key {} with increment {}  error message {}",
                        key, value, e.getMessage());
            }
            throw e;
        } finally {
            releaseInstance(jedis);
        }
    }
}
