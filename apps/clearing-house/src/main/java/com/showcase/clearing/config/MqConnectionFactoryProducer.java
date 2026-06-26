package com.showcase.clearing.config;

import com.ibm.mq.jms.MQConnectionFactory;
import com.ibm.msg.client.wmq.WMQConstants;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

import javax.jms.JMSException;

@ApplicationScoped
public class MqConnectionFactoryProducer {

    private final MqProperties props;

    @Inject
    public MqConnectionFactoryProducer(MqProperties props) {
        this.props = props;
    }

    @Produces
    @ApplicationScoped
    public MQConnectionFactory mqConnectionFactory() throws JMSException {
        MQConnectionFactory factory = new MQConnectionFactory();
        factory.setHostName(props.getHost());
        factory.setPort(props.getPort());
        factory.setQueueManager(props.getQueueManager());
        factory.setChannel(props.getChannel());
        factory.setTransportType(WMQConstants.WMQ_CM_CLIENT);
        if (!props.getUser().isBlank()) {
            factory.setStringProperty(WMQConstants.USERID, props.getUser());
            factory.setStringProperty(WMQConstants.PASSWORD, props.getPassword());
        }
        return factory;
    }
}
