package com.voyu.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "voyu.middleware")
public class MiddlewareProperties {

    private HostPort mongodb = new HostPort();
    private HostPort kafka = new HostPort();
    private HttpEndpoint elasticsearch = new HttpEndpoint();
    private MilvusEndpoint milvus = new MilvusEndpoint();
    private HttpEndpoint minio = new HttpEndpoint();

    public HostPort getMongodb() {
        return mongodb;
    }

    public void setMongodb(HostPort mongodb) {
        this.mongodb = mongodb;
    }

    public HostPort getKafka() {
        return kafka;
    }

    public void setKafka(HostPort kafka) {
        this.kafka = kafka;
    }

    public HttpEndpoint getElasticsearch() {
        return elasticsearch;
    }

    public void setElasticsearch(HttpEndpoint elasticsearch) {
        this.elasticsearch = elasticsearch;
    }

    public MilvusEndpoint getMilvus() {
        return milvus;
    }

    public void setMilvus(MilvusEndpoint milvus) {
        this.milvus = milvus;
    }

    public HttpEndpoint getMinio() {
        return minio;
    }

    public void setMinio(HttpEndpoint minio) {
        this.minio = minio;
    }

    public static class HostPort {
        private String host;
        private int port;

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }
    }

    public static class HttpEndpoint {
        private String url;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }
    }

    public static class MilvusEndpoint extends HostPort {
        private int grpcPort;
        private String healthUrl;

        public int getGrpcPort() {
            return grpcPort;
        }

        public void setGrpcPort(int grpcPort) {
            this.grpcPort = grpcPort;
        }

        public String getHealthUrl() {
            return healthUrl;
        }

        public void setHealthUrl(String healthUrl) {
            this.healthUrl = healthUrl;
        }
    }
}
