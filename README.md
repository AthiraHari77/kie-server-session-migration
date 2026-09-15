# KIE Server Stateful Session Migration — BAMOE 8.0 → 8.1

Demonstrates saving a stateful KIE session on **BAMOE 8.0 / EAP 7.4** and
restoring it on **BAMOE 8.1 / EAP 8.1** — proving session state survives a
version upgrade.

---

## How it works

```
BAMOE 8.0 (EAP 7.4 — port 8080)
  → insert 3 applicants into stateful session
  → save snapshot to disk  (.ser file)

Copy snapshot file  →  BAMOE 8.1 (EAP 8.1 — port 8180)
  → restore session from snapshot
  → insert applicant 4 → fired = 1  ✅  (engine remembers applicants 1–3)
```

Set these variables once — every command below uses them:

```bash
export EAP80=/path/to/jboss-eap-7.4    # your EAP 7.4 installation
export EAP81=/path/to/jboss-eap-8.1    # your EAP 8.1 installation
export PROJECT=/path/to/this-repo       # where you cloned this project
export KIE_USER=adminUser               # KIE Server username
export KIE_PASS=admin@Redhat1           # KIE Server password
```

---

# Phase A — BAMOE 8.0

## Step 1 — Build and inject

```bash
cd "$PROJECT"
mvn clean install -Deap.home="$EAP80"
```

Expected: `BUILD SUCCESS`

The build compiles two artifacts and automatically injects the extension JAR into
`kie-server.war/WEB-INF/lib/`:
- `loan-kjar/target/loan-kjar.jar` — the KJAR with business rules
- `session-marshal-extension/target/session-marshal-extension-1.0.0.jar` — the server extension

Confirm the extension is inside the WAR:

```bash
jar tf "$EAP80/standalone/deployments/kie-server.war" | grep session-marshal
# Expected: WEB-INF/lib/session-marshal-extension-1.0.0.jar
```

---

## Step 2 — Start EAP 7.4 with standalone-full profile

### Confirm the extension is initialized

```bash
grep "SessionMarshal extension initialized" "$EAP80/standalone/log/server.log"
# Expected: SessionMarshal extension initialized. Snapshot directory: .../standalone/data/kie-snapshots
```

---

## Step 3 — Install the KJAR into the v8.0 KIE repo

```bash
KIE_REPO="$EAP80/repositories/kie/global"
mkdir -p "$KIE_REPO/com/example/loan-kjar/1.0.0"
cp "$PROJECT/loan-kjar/target/loan-kjar.jar" "$KIE_REPO/com/example/loan-kjar/1.0.0/"
```

---

## Step 4 — Deploy the container

```bash
curl -s -u "$KIE_USER:$KIE_PASS" \
  -X PUT -H "Content-Type: application/xml" \
  "http://localhost:8080/kie-server/services/rest/server/containers/loan-container" \
  -d '<kie-container container-id="loan-container">
  <release-id>
    <group-id>com.example</group-id>
    <artifact-id>loan-kjar</artifact-id>
    <version>1.0.0</version>
  </release-id>
</kie-container>'
```

Confirm it started 

```bash
curl -s -u "$KIE_USER:$KIE_PASS" \
  -H "Accept: application/json" \
  "http://localhost:8080/kie-server/services/rest/server/containers/loan-container" \
  | grep -o '"status" : "[^"]*"'
# Expected: "status" : "STARTED"
```

---

## Step 5 — Insert facts (3 iterations)

Each call adds one applicant + one loan application to the same stateful session.
After 3 calls the session holds 6 facts.

```bash
# Iteration 1 — age 20 (Underage)
curl -s -u "$KIE_USER:$KIE_PASS" \
  -X POST -H "Content-Type: application/json" -H "Accept: application/json" \
  "http://localhost:8080/kie-server/services/rest/server/containers/instances/loan-container" \
  -d '{"lookup":"KBaseKS_stateful","commands":[
    {"insert":{"object":{"com.example.model.Applicant":{"id":"1","age":20}}}},
    {"insert":{"object":{"com.example.model.LoanApplication":{"applicantId":"1"}}}},
    {"fire-all-rules":{"out-identifier":"fired"}}]}'

# Iteration 2 — age 21 (Approved)
curl -s -u "$KIE_USER:$KIE_PASS" \
  -X POST -H "Content-Type: application/json" -H "Accept: application/json" \
  "http://localhost:8080/kie-server/services/rest/server/containers/instances/loan-container" \
  -d '{"lookup":"KBaseKS_stateful","commands":[
    {"insert":{"object":{"com.example.model.Applicant":{"id":"2","age":21}}}},
    {"insert":{"object":{"com.example.model.LoanApplication":{"applicantId":"2"}}}},
    {"fire-all-rules":{"out-identifier":"fired"}}]}'

# Iteration 3 — age 35 (Approved)
curl -s -u "$KIE_USER:$KIE_PASS" \
  -X POST -H "Content-Type: application/json" -H "Accept: application/json" \
  "http://localhost:8080/kie-server/services/rest/server/containers/instances/loan-container" \
  -d '{"lookup":"KBaseKS_stateful","commands":[
    {"insert":{"object":{"com.example.model.Applicant":{"id":"3","age":35}}}},
    {"insert":{"object":{"com.example.model.LoanApplication":{"applicantId":"3"}}}},
    {"fire-all-rules":{"out-identifier":"fired"}}]}'
```

---

## Step 6 — Save the session snapshot

```bash
curl -s -u "$KIE_USER:$KIE_PASS" \
  "http://localhost:8080/kie-server/services/rest/server/containers/instances/loan-container/ksession/marshal/KBaseKS/KBaseKS_stateful"
# Expected: Saved loan-container/KBaseKS/KBaseKS_stateful — 6 facts
```

Confirm the snapshot file is on disk:

```bash
ls -lh "$EAP80/standalone/data/kie-snapshots/snapshot-loan-container-KBaseKS-KBaseKS_stateful.ser"
```

---

## Step 7 — Stop EAP 7.4

# Phase B — BAMOE 8.1

## Step 8 — Copy the snapshot to EAP 8.1

The snapshot file is protobuf binary — compatible between KIE 7.67 and KIE 7.81.
Copy it to the EAP 8.1 data directory. The extension reads from this exact path
when `createContainer()` fires on startup.

```bash
mkdir -p "$EAP81/standalone/data/kie-snapshots"

cp "$EAP80/standalone/data/kie-snapshots/snapshot-loan-container-KBaseKS-KBaseKS_stateful.ser" \
   "$EAP81/standalone/data/kie-snapshots/"
```

---

## Step 9 — Start EAP 8.1 on port 8180

## Step 10 — Install the KJAR into the v8.1 KIE repo

```bash
KIE_REPO_81="$EAP81/repositories/kie/global"
mkdir -p "$KIE_REPO_81/com/example/loan-kjar/1.0.0"
cp "$PROJECT/loan-kjar/target/loan-kjar.jar" "$KIE_REPO_81/com/example/loan-kjar/1.0.0/"
```

---

## Step 11 — Deploy the container on v8.1

```bash
curl -s -u "$KIE_USER:$KIE_PASS" \
  -X PUT -H "Content-Type: application/xml" \
  "http://localhost:8180/kie-server/rest/server/containers/loan-container" \
  -d '<kie-container container-id="loan-container">
  <release-id>
    <group-id>com.example</group-id>
    <artifact-id>loan-kjar</artifact-id>
    <version>1.0.0</version>
  </release-id>
</kie-container>'
```

No manual restore call needed — `createContainer()` detects the snapshot file
and restores the 6 facts automatically before the container reports `STARTED`.

Confirm in the log:

```bash
grep "Restored.*loan-container" "$EAP81/standalone/log/server.log"
# Expected: Restored container=loan-container kbase=KBaseKS session=KBaseKS_stateful — 6 facts
```

## Step 12 — Verify the migration

Insert applicant 4. The engine already knows applicants 1–3 — it must fire exactly once.

```bash
curl -s -u "$KIE_USER:$KIE_PASS" \
  -X POST -H "Content-Type: application/json" -H "Accept: application/json" \
  "http://localhost:8180/kie-server/rest/server/containers/instances/loan-container" \
  -d '{"lookup":"KBaseKS_stateful","commands":[
    {"insert":{"object":{"com.example.model.Applicant":{"id":"4","age":17}}}},
    {"insert":{"object":{"com.example.model.LoanApplication":{"applicantId":"4"}}}},
    {"fire-all-rules":{"out-identifier":"fired"}}]}'
```

| `fired` | Result                                                 |
|---------|--------------------------------------------------------|
| **1** ✅ | PASS — session migrated successfully from v8.0 to v8.1 |
| **4** ❌ | FAIL — session was empty, snapshot was not restored    |
