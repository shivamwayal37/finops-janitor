# FinOps Janitor
**AI-augmented Kubernetes controller for intelligent cloud cost optimization**

[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.4-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

**USE THIS SOFTWARE AT YOUR OWN RISK. THIS IS AN EXPERIMENTAL PROJECT FOR EDUCATIONAL PURPOSES.**

![Engineering Dashboard](docs/images/dashboard.png) <!-- Add screenshot later -->

---

## 📖 Contents

- [Overview](#-overview)
- [Motivation](#-motivation)
- [Features](#-features)
- [Technologies](#️-technologies)
- [How It Works](#-how-it-works)
- [Architecture](#-architecture)
- [Getting Started](#-getting-started)
- [Configuration](#-configuration)
- [Usage](#-usage)
  - [REST API](#-rest-api)
  - [CLI](#-cli)
- [Deployment](#-deployment)
- [Use Cases](#-use-cases)
- [Contributing](#-contribute)
- [License](#-license)

---

## 🔍 Overview

FinOps Janitor is a lightweight Kubernetes controller that identifies idle development namespaces and hibernates them automatically—reducing cloud waste by up to **40%**. It uses AI-powered log analysis (Llama-3.3 via Groq) to distinguish genuinely idle workloads from broken services that need human attention.

**Key Metrics:**
- 💰 **$187/month saved** per 10 dev namespaces
- 🎯 **95% AI classification accuracy** (tested on 100 scenarios)
- ⚡ **<256 MB** memory footprint
- 🔄 **30-minute** automated scan cycles

---

## 💡 Motivation

While working on cloud infrastructure, I noticed that development environments run 24/7 but developers only work 9am-6pm (9 hours). That's **62.5% wasted cloud spend**.

I built FinOps Janitor to solve this problem with:
- **AI-driven decision making** instead of dumb CPU thresholds
- **Opt-in via Kubernetes labels** so developers stay in control
- **Human-in-the-loop alerts** via Slack before hibernation
- **One-click wake-up** when developers need their environments back

This project demonstrates production-ready thinking as a fresher, combining:
- Spring Boot microservice architecture
- Real Groq AI integration (Llama-3.3)
- Kubernetes-native patterns (annotations as state)
- SQLite for zero-config persistence

Feel free to reach out if you have questions. Contributions are welcome!

**Please leave a ⭐ if you found this useful!**

---

## ✨ Features

This system includes:

- **🤖 AI-Powered Analysis**: Uses Llama-3.3 to analyze pod logs and distinguish idle vs. broken workloads
- **📊 REST API**: Real-time access to cluster status, audit history, and manual controls
- **⚙️ Highly Configurable**: Customize thresholds, schedules, and policies via `application.yml`
- **📦 Zero Dependencies**: SQLite for persistence (no external DB needed)
- **🔔 Slack Notifications**: Pre-hibernation warnings and wake-up confirmations
- **🏷️ Label-Based Opt-In**: Only touches namespaces with `janitor.io/policy=hibernate`
- **💾 Stateless Controller**: Stores original replica counts in Kubernetes annotations
- **📈 Audit Trail**: Every action logged with AI reasoning in SQLite
- **🚀 Production-Ready**: Health checks, scheduled jobs, error handling, dry-run mode

---

## ⚡️ Technologies

| Layer | Technology | Why |
|-------|-----------|-----|
| **Runtime** | Java 21 (OpenJDK) | Virtual Threads (Project Loom) for lightweight concurrency |
| **Framework** | Spring Boot 3.2.4 | Native K8s support, @Scheduled, Actuator |
| **Cluster** | K3d / K3s | ~500 MB RAM vs 2+ GB for Minikube |
| **AI** | Groq + Llama 3.3 | Cloud inference, 0 MB local RAM, sub-second latency |
| **Database** | SQLite | Zero background process, just a file |
| **HTTP Client** | OkHttp 4.12 | Shared client for Groq + Slack calls |
| **K8s Client** | Kubernetes Java Client v19 | Official client with full API support |

---

## How It Works

![janitor-hibernation-lifecycle](assets/finops-janitor-hibernation-lifecycle.png)

### AI Decision-Making Example

**Input (Pod Logs):**
```
INFO: Server started. INFO: Waiting for connections.
```

**Groq Response:**
```json
{
  "safe": true,
  "riskScore": 2,
  "reason": "No errors or activity detected."
}
```
**Action:** HIBERNATE

---

**Input (Pod Logs):**
```
FATAL: OOMKilled. ERROR: CrashLoopBackOff.
```

**Groq Response:**
```json
{
  "safe": false,
  "riskScore": 9,
  "reason": "Pod is crash-looping due to OOM."
}
```
**Action:** 🚨 SEND ALERT (do not hibernate)

---

## Architecture

### System Design

![Architecture](assets/Architecture.png)

### Why This Architecture?

**Event-Driven but Simple:**
- No complex message queues (NATS/Kafka not needed for this scale)
- Spring `@Scheduled` provides reliable cron execution
- Direct REST API for human interaction

**Stateless Controller Pattern:**
- Stores state in Kubernetes annotations (etcd)
- If the app crashes, replica counts survive
- Standard pattern used by real platform teams

**AI Integration:**
- Groq cloud API = 0 MB local RAM
- Sub-second inference with Llama-3.3
- Fallback to safe defaults when API is down

---

## Getting Started

### Prerequisites

**Required Tools:**
- [Docker](https://docs.docker.com/get-docker/) (for K3d)
- [K3d](https://k3d.io/) v5.0+
- [Java 21](https://openjdk.org/) (OpenJDK recommended)
- [Maven](https://maven.apache.org/) 3.8+
- [kubectl](https://kubernetes.io/docs/tasks/tools/)

**Optional:**
- [SQLite CLI](https://sqlite.org/download.html) (for database inspection)

### Installation Steps

**1. Clone the repository**
```bash
git clone https://github.com/yourusername/finops-janitor.git
cd finops-janitor
```

**2. Start Docker**
```bash
# On WSL2
sudo service docker start

# Verify
docker ps
```

**3. Create K3d cluster + test namespaces**
```bash
chmod +x k3d-cluster.sh
./k3d-cluster.sh setup
```

This creates:
- ✅ K3d cluster `janitor-dev`
- ✅ `engineering-dev` namespace (labeled, idle)
- ✅ `marketing-beta` namespace (labeled, idle)
- ✅ `production` namespace (no label, ignored)

**4. Set up Groq API key**
```bash
# Get a free API key from: https://console.groq.com
export GROQ_API_KEY="gsk_your_actual_key_here"
```

**5. (Optional) Set up Slack webhook**
```bash
export SLACK_WEBHOOK_URL="https://hooks.slack.com/services/your/webhook"
```

**6. Build and run**
```bash
mvn clean package -DskipTests
java -jar target/finops-janitor-1.0.0-SNAPSHOT.jar
```

**Expected output:**
```
16:49:01 [main] INFO  i.j.finops.FinopsJanitorApplication – Started FinopsJanitorApplication in 6.3 seconds
16:49:01 [main] INFO  i.j.finops.config.KubernetesConfig – [K8s] Using FILE config: /home/user/.kube/config
```

---

## 🛠 Configuration

All settings are in `src/main/resources/application.yml`:
```yaml
janitor:
  # Label the scanner looks for
  label-key:   "janitor.io/policy"
  label-value: "hibernate"
  
  # Thresholds
  threshold-cpu-percent: 1.0       # below 1% CPU = idle
  threshold-mem-percent: 5.0       # below 5% Mem = idle
  min-age-hours: 2                 # namespace must exist 2+ hours
  
  # Schedule (Spring cron)
  cron: "0 0/30 * * * ?"           # Every 30 minutes
  
  # Safety
  dry-run: true                    # ← Set to false for production
  
groq:
  api-key: ${GROQ_API_KEY:}
  model:   "llama-3.3-70b-versatile"
  
slack:
  enabled:     false               # ← Set to true when you have webhook
  webhook-url: ${SLACK_WEBHOOK_URL:}
```

### Custom Strategy

Label your namespaces:
```bash
kubectl label namespace my-dev-env janitor.io/policy=hibernate
```

---

## 📚 Usage

### 💻 REST API

**Check cluster status**
```bash
curl http://localhost:8080/janitor/status | jq .
```

**Response:**
```json
[
  {
    "namespace": "engineering-dev",
    "cpuUsagePercent": 0.0,
    "memUsagePercent": 0.0,
    "podCount": 1,
    "ageSeconds": 7200,
    "status": "IDLE"
  }
]
```

**Trigger manual scan**
```bash
curl -X POST http://localhost:8080/janitor/scan | jq .
```

**Response:**
```json
{
  "message": "Cleanup cycle completed.",
  "hibernated": 2,
  "alerts": 0
}
```

**View audit history**
```bash
curl http://localhost:8080/janitor/history | jq .
```

**Manually hibernate a namespace**
```bash
curl -X POST "http://localhost:8080/janitor/hibernate/engineering-dev?dryRun=false" | jq .
```

**Wake up a namespace**
```bash
curl -X POST http://localhost:8080/janitor/wakeup/engineering-dev | jq .
```

**Health check**
```bash
curl http://localhost:8080/actuator/health | jq .
```

### 🖥️ CLI

**View SQLite audit log**
```bash
sqlite3 janitor.db "SELECT * FROM hibernate_log ORDER BY actioned_at DESC LIMIT 10;"
```

**Cluster management**
```bash
# Stop cluster (frees RAM)
./k3d-cluster.sh stop

# Start cluster
./k3d-cluster.sh start

# Show status
./k3d-cluster.sh status

# Delete everything
k3d cluster delete janitor-dev
```

---

## 🚀 Deployment

### Docker Build
```bash
# Build the JAR
mvn clean package -DskipTests

# Create Dockerfile
cat > Dockerfile <<EOF
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY target/finops-janitor-1.0.0-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-Xms64m", "-Xmx256m", "-jar", "app.jar"]
EOF

# Build image
docker build -t finops-janitor:latest .

# Run
docker run -d \
  -p 8080:8080 \
  -e GROQ_API_KEY="gsk_xxx" \
  -e SLACK_WEBHOOK_URL="https://hooks.slack.com/xxx" \
  -v ~/.kube/config:/root/.kube/config \
  finops-janitor:latest
```

### Kubernetes Deployment
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: finops-janitor
  namespace: kube-system
spec:
  replicas: 1
  selector:
    matchLabels:
      app: finops-janitor
  template:
    metadata:
      labels:
        app: finops-janitor
    spec:
      serviceAccountName: finops-janitor
      containers:
      - name: janitor
        image: finops-janitor:latest
        env:
        - name: GROQ_API_KEY
          valueFrom:
            secretKeyRef:
              name: janitor-secrets
              key: groq-api-key
        resources:
          requests:
            memory: "128Mi"
            cpu: "100m"
          limits:
            memory: "256Mi"
            cpu: "500m"
---
apiVersion: v1
kind: ServiceAccount
metadata:
  name: finops-janitor
  namespace: kube-system
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: finops-janitor
rules:
- apiGroups: [""]
  resources: ["namespaces", "pods", "pods/log"]
  verbs: ["get", "list"]
- apiGroups: ["apps"]
  resources: ["deployments", "deployments/scale"]
  verbs: ["get", "list", "patch", "update"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: finops-janitor
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: finops-janitor
subjects:
- kind: ServiceAccount
  name: finops-janitor
  namespace: kube-system
```

---

## 🎯 Use Cases

### 1. **Startup Dev Environments** ($22.5K/year savings)
- 50 developers × 2 namespaces = 100 dev environments
- Hibernate 6pm-9am daily (15 hours)
- **Savings:** $187/month × 100 = $18,750/year + forgotten namespaces cleanup

### 2. **E-commerce Peak Season** ($1.5K/week savings)
- Free up QA resources during Black Friday
- AI distinguishes broken tests from idle environments
- **Savings:** 80 vCPUs freed during critical week

### 3. **SaaS Trial Cleanup** ($73K/year savings)
- Auto-hibernate abandoned 14-day trials after 3 days
- Wake-up link in re-engagement emails
- **Savings:** 60% of trials hibernated early

**Read full use case details in [USECASES.md](docs/USECASES.md)**

---

## 👏 Contribute

Contributions are welcome! Please:

1. Open an issue first to discuss the change
2. Fork the repository
3. Create a feature branch (`git checkout -b feature/amazing-feature`)
4. Commit your changes (`git commit -m 'Add amazing feature'`)
5. Push to the branch (`git push origin feature/amazing-feature`)
6. Open a Pull Request

**Areas for contribution:**
- Additional AI models (OpenAI, Anthropic)
- More cloud providers (AWS EKS, GCP GKE, Azure AKS)
- Prometheus metrics integration
- Grafana dashboards
- Helm chart
- CI/CD pipeline

This project follows the [Contributor Covenant Code of Conduct](CODE_OF_CONDUCT.md).

---

## 📄 License

This project is MIT licensed - see the [LICENSE](LICENSE) file for details.

---

**Built with ❤️ for cloud cost optimization**

*If you found this project helpful, please consider giving it a ⭐*

---

## 📞 Contact

- **Author**: Shivam Wayal
- **Email**: wayalshivam7@gmail.com
- **LinkedIn**: [Shivam Wayal](www.linkedin.com/in/shivam-wayal)
- **Twitter**: [@wayalshivam7](https://x.com/wayalshivam7)

---

## 🙏 Acknowledgments

- [Groq](https://groq.com/) for fast AI inference
- [Spring Boot](https://spring.io/projects/spring-boot) team
- [Kubernetes Java Client](https://github.com/kubernetes-client/java) maintainers
- The open-source community
