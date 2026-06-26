package com.showcase.clearing.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Plain unit test for MqProperties — fields are package-private so we can set them
 * directly from the same package without needing a running CDI container.
 */
class MqPropertiesTest {

    @Test
    void getters_returnInjectedFieldValues() {
        MqProperties props = new MqProperties();
        props.host = "mqhost";
        props.port = 1414;
        props.queueManager = "QM1";
        props.channel = "DEV.APP.SVRCONN";
        props.queueName = "DEV.QUEUE.CLEARING";
        props.user = "mquser";
        props.password = "secret";

        assertEquals("mqhost", props.getHost());
        assertEquals(1414, props.getPort());
        assertEquals("QM1", props.getQueueManager());
        assertEquals("DEV.APP.SVRCONN", props.getChannel());
        assertEquals("DEV.QUEUE.CLEARING", props.getQueueName());
        assertEquals("mquser", props.getUser());
        assertEquals("secret", props.getPassword());
    }
}
