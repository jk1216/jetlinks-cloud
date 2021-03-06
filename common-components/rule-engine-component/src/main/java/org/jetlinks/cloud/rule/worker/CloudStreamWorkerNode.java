package org.jetlinks.cloud.rule.worker;

import com.alibaba.fastjson.JSON;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.jetlinks.rule.engine.api.Logger;
import org.jetlinks.rule.engine.api.RuleData;
import org.jetlinks.rule.engine.api.executor.ExecutableRuleNode;
import org.jetlinks.rule.engine.api.executor.ExecutionContext;
import org.jetlinks.rule.engine.api.model.NodeType;
import org.jetlinks.rule.engine.executor.AbstractExecutableRuleNodeFactoryStrategy;
import org.jetlinks.rule.engine.executor.supports.RuleNodeConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.cloud.stream.annotation.EnableBinding;
import org.springframework.cloud.stream.binding.BinderAwareChannelResolver;
import org.springframework.cloud.stream.binding.BindingService;
import org.springframework.messaging.*;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * @author zhouhao
 * @since 1.0.0
 */
@Component
@EnableBinding
@ConditionalOnClass(BinderAwareChannelResolver.class)
@Slf4j
public class CloudStreamWorkerNode extends AbstractExecutableRuleNodeFactoryStrategy<CloudStreamWorkerNode.Config> {

    @Autowired
    private BinderAwareChannelResolver resolver;

    @Autowired
    private BindingService bindingService;

    @Override
    public Config newConfig() {
        return new Config();
    }

    @Override
    public String getSupportType() {
        return "cloud-stream";
    }

    protected Map<Integer, Map<Integer, Consumer<Object>>> allConsumer = new ConcurrentHashMap<>();

    protected Map<Integer, MessageHandler> handlerMap = new ConcurrentHashMap<>();

    @Override
    public Function<RuleData, CompletionStage<Object>> createExecutor(ExecutionContext context, Config config) {

        throw new UnsupportedOperationException();
    }

    private Object convertPayload(Object payload) {
        if (payload instanceof byte[]) {
            payload = new String((byte[]) payload);
        }
        if (payload instanceof String) {
            String stringPayload = ((String) payload);
            if (stringPayload.startsWith("[")) {
                return JSON.parseArray(stringPayload);
            } else if (stringPayload.startsWith("{")) {
                return JSON.parseObject(stringPayload);
            }
        }
        return payload;
    }

    private void addListener(String topic, SubscribableChannel channel, Consumer<Object> consumer) {
        AtomicReference<Boolean> first = new AtomicReference<>(false);

        int hash = System.identityHashCode(channel);
        allConsumer.computeIfAbsent(hash, $ -> {
            first.set(true);
            return new LinkedHashMap<>();
        }).put(System.identityHashCode(consumer), consumer);

        if (first.get()) {
            MessageHandler handler = msg -> {
                Map<Integer, Consumer<Object>> consumers = allConsumer.get(hash);
                if (!CollectionUtils.isEmpty(consumers)) {
                    boolean allFail = true;
                    Throwable lastError = null;
                    for (Consumer<Object> c : consumers.values()) {
                        try {
                            c.accept(convertPayload(msg.getPayload()));
                            allFail = false;
                        } catch (Throwable e) {
                            lastError = e;
                            log.error(e.getMessage(), e);
                        }
                    }
                    if (allFail && lastError != null) {
                        throw new RuntimeException(lastError);
                    }
                }
            };
            handlerMap.put(hash, handler);
            channel.subscribe(handler);
            bindingService.bindConsumer(channel, topic);
        }

    }


    private void removeListener(String topic, SubscribableChannel channel, Consumer<Object> consumer) {
        int hash = System.identityHashCode(channel);
        int consumerHash = System.identityHashCode(consumer);
        log.debug("remove [{}] listener :[{}]", topic, consumerHash);
        Map<Integer, Consumer<Object>> consumers = allConsumer.get(hash);
        if (!CollectionUtils.isEmpty(consumers)) {
            consumers.remove(consumerHash);
        }
        if (CollectionUtils.isEmpty(consumers)) {
            bindingService.unbindConsumers(topic);
            MessageHandler handler = handlerMap.remove(hash);
            if (null != handler) {
                channel.unsubscribe(handler);
            }
            allConsumer.remove(hash);
        }
    }

    @Override
    protected ExecutableRuleNode doCreate(Config config) {
        MessageChannel messageChannel = resolver.resolveDestination(config.getTopic());
        String topic = config.getTopic();

        AtomicBoolean started = new AtomicBoolean();
        return context -> {
            if (started.get()) {
                return;
            }
            log.info("start spring cloud stream {} : {}", config.getType(), config.getTopic());
            started.set(true);
            if (config.type == Type.Consumer && messageChannel instanceof SubscribableChannel) {
                SubscribableChannel channel = ((SubscribableChannel) messageChannel);
                Consumer<Object> consumer = data -> context.getOutput().write(RuleData.create(data));

                addListener(topic, channel, consumer);

                context.getInput()
                        .acceptOnce(ruleData -> context.getOutput()
                                .write(ruleData.newData(ruleData.getData())));

                context.onStop(() -> {
                    removeListener(topic, channel, consumer);
                    started.set(false);
                });

            } else {
                context.getInput()
                        .acceptOnce(ruleData -> {
                            messageChannel.send(MessageBuilder.withPayload(ruleData.getData()).build());
                            context.getOutput()
                                    .write(ruleData.newData(ruleData.getData()));
                        });
            }
        };
    }

    public enum Type {
        Consumer, Producer
    }

    @Getter
    @Setter
    public static class Config implements RuleNodeConfig {

        private String topic;

        private Type type = Type.Consumer;

        @Override
        public NodeType getNodeType() {
            return NodeType.PEEK;
        }

        @Override
        public void setNodeType(NodeType nodeType) {

        }
    }
}
