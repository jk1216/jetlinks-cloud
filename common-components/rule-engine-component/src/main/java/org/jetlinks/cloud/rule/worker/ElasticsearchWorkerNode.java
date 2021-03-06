package org.jetlinks.cloud.rule.worker;

import com.google.gson.Gson;
import io.searchbox.action.Action;
import io.searchbox.client.JestClient;
import io.searchbox.client.JestClientFactory;
import io.searchbox.client.JestResult;
import io.searchbox.client.JestResultHandler;
import io.searchbox.client.config.HttpClientConfig;
import io.searchbox.core.Bulk;
import io.searchbox.core.Index;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import org.apache.http.HttpHost;
import org.hswebframework.web.ExpressionUtils;
import org.hswebframework.web.id.IDGenerator;
import org.jetlinks.rule.engine.api.RuleData;
import org.jetlinks.rule.engine.api.executor.ExecutionContext;
import org.jetlinks.rule.engine.api.model.NodeType;
import org.jetlinks.rule.engine.executor.AbstractExecutableRuleNodeFactoryStrategy;
import org.jetlinks.rule.engine.executor.supports.RuleNodeConfig;
import org.springframework.boot.autoconfigure.elasticsearch.jest.JestProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

@Component
public class ElasticsearchWorkerNode extends AbstractExecutableRuleNodeFactoryStrategy<ElasticsearchWorkerNode.Config> {

    @Override
    public Config newConfig() {
        return new Config();
    }

    @Override
    public String getSupportType() {
        return "elasticsearch";
    }

    @Override
    public Function<RuleData, CompletionStage<Object>> createExecutor(ExecutionContext context, Config config) {
        JestClient client = config.createClient();

        context.onStop(client::shutdownClient);

        return ruleData -> {
            CompletableFuture<Object> future = new CompletableFuture<>();

            client.executeAsync(config.createAction(ruleData), new JestResultHandler<JestResult>() {
                @Override
                public void completed(JestResult jestResult) {
                    if (!jestResult.isSucceeded()) {
                        context.logger()
                                .error("保存数据到ES失败:{}", jestResult.getJsonString());
                        future.completeExceptionally(new IllegalStateException(jestResult.getErrorMessage()));
                    } else {
                        //返回原数据
                        future.complete(ruleData.newData(ruleData.getData()));
                    }
                }

                @Override
                public void failed(Exception e) {
                    context.logger()
                            .error("保存数据到ES失败", e);
                    future.completeExceptionally(e);
                }
            });
            return future;
        };
    }

    @Getter
    @Setter
    public static class Config extends JestProperties implements RuleNodeConfig {
        private NodeType nodeType;

        private String id;

        private String index;

        private String type;

        protected HttpClientConfig createHttpClientConfig() {
            HttpClientConfig.Builder builder = new HttpClientConfig.Builder(
                    this.getUris());
            if (StringUtils.hasText(this.getUsername())) {
                builder.defaultCredentials(this.getUsername(),
                        this.getPassword());
            }
            String proxyHost = this.getProxy().getHost();
            if (StringUtils.hasText(proxyHost)) {
                Integer proxyPort = this.getProxy().getPort();
                Assert.notNull(proxyPort, "Proxy port must not be null");
                builder.proxy(new HttpHost(proxyHost, proxyPort));
            }

            builder.multiThreaded(this.isMultiThreaded());
            builder.connTimeout(this.getConnectionTimeout())
                    .readTimeout(this.getReadTimeout());
            return builder.build();
        }

        public JestClient createClient() {
            JestClientFactory factory = new JestClientFactory();
            factory.setHttpClientConfig(createHttpClientConfig());
            return factory.getObject();
        }

        @SneakyThrows
        private String getExpression(String p, Map<String, Object> data) {
            if (StringUtils.isEmpty(p)) {
                return p;
            }
            return ExpressionUtils.analytical(p, data, "spel");
        }

        public Action<?> createAction(RuleData ruleData) {
            Bulk.Builder bulkBuilder = new Bulk.Builder();

            ruleData.acceptMap(map -> {
                String indexId = id;
                if (StringUtils.isEmpty(indexId)) {
                    indexId = IDGenerator.MD5.generate();
                } else {
                    indexId = Optional.ofNullable(map.get(getId())).map(String::valueOf).orElseGet(() -> getExpression(getId(), map));
                }
                Index index = new Index.Builder(map)
                        .id(indexId)
                        .index(Optional.ofNullable(map.get(getIndex())).map(String::valueOf).orElseGet(() -> getExpression(getIndex(), map)))
                        .type(Optional.ofNullable(map.get(getType())).map(String::valueOf).orElseGet(() -> getExpression(getType(), map)))
                        .build();

                bulkBuilder.addAction(index);
            });

            return bulkBuilder.build();

        }
    }

}
