package com.showcase.clearing.config;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class MqProperties {

    @ConfigProperty(name = "clearing.mq.host")
    String host;

    @ConfigProperty(name = "clearing.mq.port")
    int port;

    @ConfigProperty(name = "clearing.mq.queue-manager")
    String queueManager;

    @ConfigProperty(name = "clearing.mq.channel")
    String channel;

    @ConfigProperty(name = "clearing.mq.queue-name")
    String queueName;

    @ConfigProperty(name = "clearing.mq.user", defaultValue = "")
    String user;

    @ConfigProperty(name = "clearing.mq.password", defaultValue = "")
    String password;

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public String getQueueManager() {
        return queueManager;
    }

    public String getChannel() {
        return channel;
    }

    public String getQueueName() {
        return queueName;
    }

    public String getUser() {
        return user;
    }

    public String getPassword() {
        return password;
    }
}
