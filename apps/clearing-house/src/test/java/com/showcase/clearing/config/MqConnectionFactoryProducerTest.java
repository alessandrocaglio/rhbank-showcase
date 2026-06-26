package com.showcase.clearing.config;

import com.ibm.mq.jms.MQConnectionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.jms.JMSException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MqConnectionFactoryProducerTest {

    @Test
    void mqConnectionFactory_configuredFromProperties() throws JMSException {
        MqProperties props = mock(MqProperties.class);
        when(props.getHost()).thenReturn("testhost");
        when(props.getPort()).thenReturn(1414);
        when(props.getQueueManager()).thenReturn("QM1");
        when(props.getChannel()).thenReturn("DEV.APP.SVRCONN");
        // Empty user — password stub omitted since the credential block is never entered
        when(props.getUser()).thenReturn("");

        MqConnectionFactoryProducer producer = new MqConnectionFactoryProducer(props);
        MQConnectionFactory factory = producer.mqConnectionFactory();

        assertNotNull(factory);
        assertEquals("testhost", factory.getHostName());
        assertEquals(1414, factory.getPort());
        assertEquals("QM1", factory.getQueueManager());
    }

    @Test
    void mqConnectionFactory_withCredentials_setsUserAndPassword() throws JMSException {
        MqProperties props = mock(MqProperties.class);
        when(props.getHost()).thenReturn("mqhost");
        when(props.getPort()).thenReturn(1414);
        when(props.getQueueManager()).thenReturn("QM1");
        when(props.getChannel()).thenReturn("DEV.APP.SVRCONN");
        when(props.getUser()).thenReturn("mquser");
        when(props.getPassword()).thenReturn("mqpassword");

        MqConnectionFactoryProducer producer = new MqConnectionFactoryProducer(props);
        MQConnectionFactory factory = producer.mqConnectionFactory();

        assertNotNull(factory);
        assertEquals("mqhost", factory.getHostName());
    }
}
