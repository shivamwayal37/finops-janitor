#!/bin/bash
# ════════════════════════════════════════════════════════════════════
# k3d-cluster.sh  –  Cold Start / Stop script for the dev K3d cluster
#
# Usage:
#   ./k3d-cluster.sh start    – create + start the cluster (if needed)
#   ./k3d-cluster.sh stop     – stop the cluster (frees ~1 GB RAM)
#   ./k3d-cluster.sh status   – show cluster status + node health
#   ./k3d-cluster.sh setup    – start cluster AND deploy test namespaces
#
# Why?
#   Running K3d 24/7 wastes RAM.  This script lets you spin it up only
#   when you're actively developing, and tear it down when you're done.
# ════════════════════════════════════════════════════════════════════

set -e

CLUSTER_NAME="janitor-dev"
AGENTS=1                         # 1 agent node = ~500 MB total

# ── Colors for pretty output ───────────────────────────────────────
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'   # No Color

# ════════════════════════════════════════════════════════════════════
#  FUNCTIONS
# ════════════════════════════════════════════════════════════════════

start_cluster() {
    echo -e "${GREEN}[k3d] Checking if cluster '${CLUSTER_NAME}' exists...${NC}"

    if k3d cluster list | grep -q "${CLUSTER_NAME}"; then
        echo -e "${YELLOW}[k3d] Cluster already exists. Starting...${NC}"
        k3d cluster start "${CLUSTER_NAME}"
    else
        echo -e "${GREEN}[k3d] Creating cluster '${CLUSTER_NAME}' with ${AGENTS} agent(s)...${NC}"
        k3d cluster create "${CLUSTER_NAME}" --agents "${AGENTS}"
    fi

    echo -e "${GREEN}[k3d] Cluster is UP. Verifying nodes...${NC}"
    kubectl get nodes --context "k3d-${CLUSTER_NAME}"
}

stop_cluster() {
    echo -e "${YELLOW}[k3d] Stopping cluster '${CLUSTER_NAME}'...${NC}"

    if k3d cluster list | grep -q "${CLUSTER_NAME}"; then
        k3d cluster stop "${CLUSTER_NAME}"
        echo -e "${GREEN}[k3d] Cluster stopped. RAM freed.${NC}"
    else
        echo -e "${YELLOW}[k3d] Cluster '${CLUSTER_NAME}' not found. Nothing to stop.${NC}"
    fi
}

show_status() {
    echo -e "${GREEN}[k3d] Cluster Status:${NC}"
    k3d cluster list

    echo ""
    echo -e "${GREEN}[k3d] Node Health:${NC}"
    kubectl get nodes --context "k3d-${CLUSTER_NAME}" 2>/dev/null || \
        echo -e "${RED}[k3d] Could not reach cluster.${NC}"

    echo ""
    echo -e "${GREEN}[k3d] Namespaces:${NC}"
    kubectl get namespaces --context "k3d-${CLUSTER_NAME}" 2>/dev/null || \
        echo -e "${RED}[k3d] Could not list namespaces.${NC}"
}

# ─── Deploy two test namespaces for the Janitor to scan ────────────
# Namespace 1: "engineering-dev"  → labeled, has a running nginx pod  → should be detected as IDLE
# Namespace 2: "marketing-beta"   → labeled, has a running nginx pod  → should be detected as IDLE
# Namespace 3: "production"       → NO label                          → Janitor should IGNORE this

setup_test_namespaces() {
    start_cluster
    echo ""
    echo -e "${GREEN}[setup] Creating test namespaces...${NC}"

    CONTEXT="k3d-${CLUSTER_NAME}"

    # ── Namespace 1: engineering-dev (opt-in, idle) ──────────────────
    kubectl create namespace engineering-dev --context "${CONTEXT}" --dry-run=client -o yaml | \
        kubectl apply -f - --context "${CONTEXT}"

    kubectl label namespace engineering-dev \
        "janitor.io/policy=hibernate" \
        --context "${CONTEXT}" --overwrite

    # Create a DEPLOYMENT (not just a pod)
    cat <<EOF | kubectl apply -f - --context "${CONTEXT}"
apiVersion: apps/v1
kind: Deployment
metadata:
  name: nginx-idle
  namespace: engineering-dev
spec:
  replicas: 2
  selector:
    matchLabels:
      app: nginx
  template:
    metadata:
      labels:
        app: nginx
    spec:
      containers:
      - name: nginx
        image: nginx:alpine
        ports:
        - containerPort: 80
EOF

    echo -e "  ${GREEN}✓ engineering-dev created (labeled, 2 nginx replicas)${NC}"

    # ── Namespace 2: marketing-beta (opt-in, idle) ───────────────────
    kubectl create namespace marketing-beta --context "${CONTEXT}" --dry-run=client -o yaml | \
        kubectl apply -f - --context "${CONTEXT}"

    kubectl label namespace marketing-beta \
        "janitor.io/policy=hibernate" \
        --context "${CONTEXT}" --overwrite

    cat <<EOF | kubectl apply -f - --context "${CONTEXT}"
apiVersion: apps/v1
kind: Deployment
metadata:
  name: nginx-beta
  namespace: marketing-beta
spec:
  replicas: 3
  selector:
    matchLabels:
      app: nginx
  template:
    metadata:
      labels:
        app: nginx
    spec:
      containers:
      - name: nginx
        image: nginx:alpine
        ports:
        - containerPort: 80
EOF

    echo -e "  ${GREEN}✓ marketing-beta created (labeled, 3 nginx replicas)${NC}"

    # ── Namespace 3: production (NO label — Janitor must ignore this) ─
    kubectl create namespace production --context "${CONTEXT}" --dry-run=client -o yaml | \
        kubectl apply -f - --context "${CONTEXT}"

    cat <<EOF | kubectl apply -f - --context "${CONTEXT}"
apiVersion: apps/v1
kind: Deployment
metadata:
  name: nginx-prod
  namespace: production
spec:
  replicas: 5
  selector:
    matchLabels:
      app: nginx
  template:
    metadata:
      labels:
        app: nginx
    spec:
      containers:
      - name: nginx
        image: nginx:alpine
        ports:
        - containerPort: 80
EOF

    echo -e "  ${GREEN}✓ production created (NO label — should be ignored)${NC}"

    # ── Wait for pods to be Running ───────────────────────────────────
    echo ""
    echo -e "${YELLOW}[setup] Waiting for pods to reach Running state (up to 60s)...${NC}"
    kubectl wait --for=condition=available deployment --all \
        --namespace=engineering-dev --context="${CONTEXT}" --timeout=60s
    kubectl wait --for=condition=available deployment --all \
        --namespace=marketing-beta  --context="${CONTEXT}" --timeout=60s
    kubectl wait --for=condition=available deployment --all \
        --namespace=production      --context="${CONTEXT}" --timeout=60s

    echo ""
    echo -e "${GREEN}[setup] Done! Run the Janitor to see it detect engineering-dev and marketing-beta.${NC}"
    echo ""
    echo "    Test hibernation:"
    echo "    curl -X POST 'http://localhost:8080/janitor/hibernate/engineering-dev?dryRun=false'"
}

# ════════════════════════════════════════════════════════════════════
#  MAIN
# ════════════════════════════════════════════════════════════════════

case "$1" in
    start)   start_cluster          ;;
    stop)    stop_cluster           ;;
    status)  show_status            ;;
    setup)   setup_test_namespaces  ;;
    *)
        echo -e "${YELLOW}Usage: $0 {start|stop|status|setup}${NC}"
        echo ""
        echo "  start   – Start (or create) the K3d cluster"
        echo "  stop    – Stop the cluster and free RAM"
        echo "  status  – Show cluster + namespace health"
        echo "  setup   – Start cluster AND deploy test namespaces"
        exit 1
        ;;
esac
