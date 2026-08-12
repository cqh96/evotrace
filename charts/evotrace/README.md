# EvoTrace Helm Chart

用于在 Kubernetes 上部署 **EvoTrace 平台** 的 Helm Chart，包含三个组件：

| 组件 | Deployment | 说明 |
| --- | --- | --- |
| `evotrace-server` | `*-server` | Spring Boot 服务端，对外暴露 HTTP 服务（端口 8080） |
| `evotrace-worker` | `*-worker` | AI 异步消费者，从 Kafka 消费任务并调用 AI 模型 |
| `evotrace-ui` | 由 server 提供 | 前端静态资源（镜像预留，当前由 server 承载） |

> 说明：本 Chart 的 `values.yaml` 预留了 `image.ui` 配置项。若前端静态资源由独立服务承载，可在此基础上扩展一个 `deployment-ui.yaml`。

## 前置依赖

在安装前需要具备以下环境与依赖：

1. **Kubernetes 集群**（建议 v1.22+，支持 `networking.k8s.io/v1` Ingress 与 `autoscaling/v2` HPA、`policy/v1` PDB）。
2. **Ingress Controller**（如 nginx-ingress），用于对外暴露 `evotrace-server`；若 `ingress.enabled=false` 可不安装。
3. **外部依赖服务**（默认通过 `external.*` 指向集群内同名服务，也可改为托管实例地址）：

   - **PostgreSQL**：EvoTrace 主数据库。
   - **Redis**：缓存 / 会话存储。
   - **Kafka**：AI 任务消息队列。
   - **ClickHouse**：时序分析存储。
   - **MinIO**（或任意 S3 兼容对象存储）：文件存储。

   若这些服务尚未部署，可自行运维或使用各依赖的官方 Helm Chart 先安装，再通过 `values` 将连接地址指向它们。
4. **镜像仓库凭据**（可选）：私有仓库需配置 `imagePullSecrets`。

## 安装

### 1. 创建命名空间

```bash
kubectl create namespace evotrace
```

### 2. 覆盖必要配置

至少应覆盖外部依赖的地址与口令，以及 AI API Key。建议使用 `values-prod.yaml` 作为生产基线，并用 `--set` 覆盖连接信息：

```bash
helm install evotrace charts/evotrace -n evotrace \
  -f charts/evotrace/values-prod.yaml \
  --set ingress.host=your-domain.example.com \
  --set ingress.tls.secretName=your-tls-secret \
  --set external.postgresql.url="jdbc:postgresql://pg-host:5432/evotrace" \
  --set external.postgresql.password='<db-password>' \
  --set external.redis.host=redis-host \
  --set external.redis.password='<redis-password>' \
  --set external.kafka.bootstrapServers="kafka-host:9092" \
  --set external.clickhouse.url="jdbc:clickhouse://ch-host:8123/evotrace" \
  --set external.clickhouse.password='<ch-password>' \
  --set external.minio.endpoint="https://minio-host" \
  --set external.minio.accessKey='<access-key>' \
  --set external.minio.secretKey='<secret-key>' \
  --set app.ai.apiKey='<ark-api-key>'
```

### 3. 默认安装（开发/测试，使用默认连接）

```bash
helm install evotrace charts/evotrace -n evotrace
```

## values 覆盖示例

### 调整副本数

```bash
--set replicaCount.server=3 --set replicaCount.worker=2
```

### 使用私有镜像仓库

```bash
helm install evotrace charts/evotrace -n evotrace \
  --set image.server.repository=my-registry/evotrace-server \
  --set image.worker.repository=my-registry/evotrace-worker \
  --set image.tag=v1.2.3 \
  --set imagePullSecrets[0].name=regcred
```

### 覆盖任意 Spring 配置项

环境变量统一以 `EVOTRACE_` 为前缀，key 转大写、`.` 转 `_` 后注入，供 Spring Boot relaxed binding 覆盖。例如覆盖日志级别：

```bash
--set-string app.extraEnv.LOG_LEVEL=DEBUG
```

等价于注入环境变量 `LOG_LEVEL=DEBUG`。

## 升级

```bash
helm upgrade evotrace charts/evotrace -n evotrace -f charts/evotrace/values-prod.yaml
```

## 回滚

```bash
# 查看历史版本
helm history evotrace -n evotrace

# 回滚到上一个版本
helm rollback evotrace 1 -n evotrace
```

## 卸载

```bash
helm uninstall evotrace -n evotrace
```

## 健康检查验证

### 1. 查看 Pod 状态

```bash
kubectl get pods -n evotrace -o wide
```

等待所有 Pod 进入 `Running` 且 `READY 1/1`。

### 2. 检查 Service

```bash
kubectl get svc -n evotrace
kubectl get endpoints -n evotrace
```

### 3. 验证健康检查端点

```bash
# 通过端口转发验证 server 健康状态
kubectl port-forward svc/evotrace 8080:8080 -n evotrace
curl http://localhost:8080/actuator/health
```

### 4. 查看日志

```bash
kubectl logs -n evotrace -l app.kubernetes.io/name=evotrace,app.kubernetes.io/component=server
kubectl logs -n evotrace -l app.kubernetes.io/name=evotrace,app.kubernetes.io/component=worker
```

### 5. 验证 Ingress（若启用）

```bash
curl -k https://your-domain.example.com/actuator/health
```

## 配置项总览

| Key | 默认值 | 说明 |
| --- | --- | --- |
| `image.server.repository` | `registry.example.com/evotrace-server` | server 镜像仓库 |
| `image.worker.repository` | `registry.example.com/evotrace-worker` | worker 镜像仓库 |
| `image.tag` | 空（用 appVersion） | 全局镜像 tag |
| `image.pullPolicy` | `IfNotPresent` | 镜像拉取策略 |
| `replicaCount.server` | `2` | server 副本数 |
| `replicaCount.worker` | `1` | worker 副本数 |
| `service.type/port` | `ClusterIP/8080` | server 服务 |
| `ingress.enabled` | `false` | 是否启用 Ingress |
| `autoscaling.enabled` | `false` | 是否启用 server 的 HPA |
| `podDisruptionBudget.enabled` | `false` | 是否启用 PDB |
| `external.*` | 见 values.yaml | 外部依赖连接 |
| `app.ai.*` | 见 values.yaml | AI 接入配置 |
| `app.evotrace.*` | 见 values.yaml | 平台自身配置 |

完整默认值见 [values.yaml](./values.yaml)，生产覆盖基线见 [values-prod.yaml](./values-prod.yaml)。

## 安全提示

- 默认口令（`changeme-*`）仅用于演示，**生产环境必须覆盖**。
- 敏感口令集中在 Secret 中，`ConfigMap` 不包含任何口令。
- 若需让应用读取其它 Secret，可开启 `rbac.enabled` 并配置 `rbac.secretNames`。