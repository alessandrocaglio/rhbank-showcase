package com.showcase.clearing.messaging;

import com.ibm.mq.jms.MQConnectionFactory;
import com.showcase.clearing.config.MqProperties;
import com.showcase.clearing.dto.ClearingResult;
import com.showcase.clearing.service.ClearingService;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.jms.JMSContext;
import javax.jms.JMSException;
import javax.jms.JMSRuntimeException;
import javax.jms.Queue;
import javax.jms.TextMessage;
import java.util.List;

@Startup
@ApplicationScoped
public class MqMessageListener {

    private static final Logger LOG = Logger.getLogger(MqMessageListener.class);

    // Custom TextMapGetter that reads W3C traceparent/tracestate from JMS String properties
    private static final TextMapGetter<TextMessage> JMS_GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(TextMessage carrier) {
            return List.of("traceparent", "tracestate");
        }

        @Override
        public String get(TextMessage carrier, String key) {
            try {
                return carrier.getStringProperty(key);
            } catch (JMSException e) {
                return null;
            }
        }
    };

    private final MQConnectionFactory connectionFactory;
    private final ClearingService clearingService;
    private final PaymentCompletedPublisher publisher;
    private final String queueName;

    @Inject
    public MqMessageListener(MQConnectionFactory connectionFactory,
                              ClearingService clearingService,
                              PaymentCompletedPublisher publisher,
                              MqProperties mqProperties) {
        this.connectionFactory = connectionFactory;
        this.clearingService = clearingService;
        this.publisher = publisher;
        this.queueName = mqProperties.getQueueName();
    }

    @PostConstruct
    public void startListening() {
        Thread listenerThread = new Thread(this::listenLoop, "mq-listener");
        listenerThread.setDaemon(true);
        listenerThread.start();
    }

    private void listenLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try (JMSContext context = connectionFactory.createContext()) {
                Queue queue = context.createQueue(queueName);
                javax.jms.JMSConsumer consumer = context.createConsumer(queue);
                LOG.info("MQ listener started, waiting for messages on queue");
                while (!Thread.currentThread().isInterrupted()) {
                    javax.jms.Message message = consumer.receive();
                    if (message instanceof TextMessage textMessage) {
                        try {
                            processMessage(textMessage);
                        } catch (JMSException e) {
                            LOG.errorf(e, "Failed to process JMS message");
                            Span.current().recordException(e);
                        }
                    }
                }
            } catch (JMSRuntimeException e) {
                LOG.errorf(e, "JMS connection lost, retrying in 5 seconds");
                try {
                    Thread.sleep(5_000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }


    void processMessage(TextMessage message) throws JMSException {
        String traceparent = message.getStringProperty("traceparent");
        String transactionId = message.getStringProperty("transactionId");
        String payload = message.getText();

        LOG.infof("Received JMS message for transactionId=%s traceparent=%s", transactionId, traceparent);

        // Restore W3C trace context propagated from transaction-engine via JMS String property.
        // IBM MQ JMS is not reliably intercepted by the OTel agent, so this explicit extraction
        // is the safety net mandated by TRACING.md Boundary 3.
        Context extractedContext = GlobalOpenTelemetry.getPropagators()
                .getTextMapPropagator()
                .extract(Context.current(), message, JMS_GETTER);

        try (Scope scope = extractedContext.makeCurrent()) {
            Span span = GlobalOpenTelemetry.getTracer("clearing-house")
                    .spanBuilder("clearing-house.process")
                    .setParent(extractedContext)
                    .startSpan();
            try (Scope spanScope = span.makeCurrent()) {
                span.setAttribute("bank.payment.transaction_id", transactionId != null ? transactionId : "unknown");

                ClearingResult result = clearingService.process(transactionId);

                span.setAttribute("bank.clearing.status", result.status());

                publisher.publish(result);

                LOG.infof("Clearing completed for transactionId=%s status=%s", transactionId, result.status());
            } catch (Exception e) {
                span.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR, e.getMessage());
                span.recordException(e);
                throw e;
            } finally {
                span.end();
            }
        }
    }
}
