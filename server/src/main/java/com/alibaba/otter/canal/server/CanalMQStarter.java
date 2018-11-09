package com.alibaba.otter.canal.server;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.otter.canal.common.MQProperties;
import com.alibaba.otter.canal.instance.core.CanalInstance;
import com.alibaba.otter.canal.instance.core.CanalMQConfig;
import com.alibaba.otter.canal.kafka.CanalKafkaProducer;
import com.alibaba.otter.canal.protocol.ClientIdentity;
import com.alibaba.otter.canal.protocol.Message;
import com.alibaba.otter.canal.server.embedded.CanalServerWithEmbedded;
import com.alibaba.otter.canal.spi.CanalMQProducer;

public class CanalMQStarter {

    private static final Logger     logger  = LoggerFactory.getLogger(CanalMQStarter.class);

    private volatile boolean        running = false;

    private ExecutorService         executorService;

    private CanalMQProducer         canalMQProducer;

    private MQProperties            properties;

    private CanalServerWithEmbedded canalServer;

    public CanalMQStarter(CanalMQProducer canalMQProducer){
        this.canalMQProducer = canalMQProducer;
    }

    public synchronized void start(MQProperties properties) {
        try {
            if (running) {
                return;
            }
            this.properties = properties;
            canalMQProducer.init(properties);
            // set filterTransactionEntry
            if (properties.isFilterTransactionEntry()) {
                System.setProperty("canal.instance.filter.transaction.entry", "true");
            }

            if (properties.getFlatMessage()) {
                // 针对flat message模式,设置为raw避免ByteString->Entry的二次解析
                System.setProperty("canal.instance.memory.rawEntry", "false");
            }

            canalServer = CanalServerWithEmbedded.instance();

            // 对应每个instance启动一个worker线程
            executorService = Executors.newFixedThreadPool(canalServer.getCanalInstances().size());
            logger.info("## start the MQ workers.");
            for (final CanalInstance canalInstance : canalServer.getCanalInstances().values()) {
                executorService.execute(new Runnable() {

                    @Override
                    public void run() {
                        MQProperties.CanalDestination destination = new MQProperties.CanalDestination();
                        destination.setCanalDestination(canalInstance.getDestination());
                        CanalMQConfig mqConfig = canalInstance.getMqConfig();
                        destination.setTopic(mqConfig.getTopic());
                        destination.setPartition(mqConfig.getPartition());
                        destination.setPartitionsNum(mqConfig.getPartitionsNum());
                        destination.setPartitionHash(mqConfig.getPartitionHashProperties());
                        worker(destination);
                    }
                });
            }

            running = true;
            logger.info("## the MQ workers is running now ......");
            Runtime.getRuntime().addShutdownHook(new Thread() {

                public void run() {
                    stop();
                }

            });

        } catch (Throwable e) {
            logger.error("## Something goes wrong when starting up the canal MQ workers:", e);
            System.exit(0);
        }
    }

    public synchronized void stop() {
        if (!running) {
            return;
        }
        try {
            logger.info("## stop the MQ workers");
            running = false;
            executorService.shutdown();
            canalMQProducer.stop();
        } catch (Throwable e) {
            logger.warn("##something goes wrong when stopping MQ workers:", e);
        } finally {
            logger.info("## canal MQ is down.");
        }
    }

    private void worker(MQProperties.CanalDestination destination) {
        while (!running)
            ;
        logger.info("## start the MQ producer: {}.", destination.getCanalDestination());

        final ClientIdentity clientIdentity = new ClientIdentity(destination.getCanalDestination(), (short) 1001, "");
        while (running) {
            try {
                if (!canalServer.getCanalInstances().containsKey(clientIdentity.getDestination())) {
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException e) {
                        // ignore
                    }
                    continue;
                }
                canalServer.subscribe(clientIdentity);
                logger.info("## the MQ producer: {} is running now ......", destination.getCanalDestination());

                Long getTimeout = properties.getCanalGetTimeout();
                int getBatchSize = properties.getCanalBatchSize();
                while (running) {
                    Message message;
                    if (getTimeout != null && getTimeout > 0) {
                        message = canalServer
                            .getWithoutAck(clientIdentity, getBatchSize, getTimeout, TimeUnit.MILLISECONDS);
                    } else {
                        message = canalServer.getWithoutAck(clientIdentity, getBatchSize);
                    }

                    final long batchId = message.getId();
                    try {
                        int size = message.isRaw() ? message.getRawEntries().size() : message.getEntries().size();
                        if (batchId != -1 && size != 0) {
                            canalMQProducer.send(destination, message, new CanalKafkaProducer.Callback() {

                                @Override
                                public void commit() {
                                    canalServer.ack(clientIdentity, batchId); // 提交确认
                                }

                                @Override
                                public void rollback() {
                                    canalServer.rollback(clientIdentity, batchId);
                                }
                            }); // 发送message到topic
                        } else {
                            try {
                                Thread.sleep(100);
                            } catch (InterruptedException e) {
                                // ignore
                            }
                        }

                    } catch (Exception e) {
                        logger.error(e.getMessage(), e);
                    }
                }
            } catch (Exception e) {
                logger.error("process error!", e);
            }
        }
    }
}
