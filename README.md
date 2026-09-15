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

---

## Prerequisites

|             | BAMOE 8.0       | BAMOE 8.1              |
|-------------|-----------------|------------------------|
| EAP         | 7.4             | 8.1                    |
| Port        | 8080            | 8180 (port offset 100) |
| Java        | 11              | 17                     |
| KIE version | 7.67.x          | 7.81.x                 |
| Maven       | 3.8+            | (same build)           |

Set these variables once — every command below uses them:

```bash
export EAP80=/path/to/jboss-eap-7.4    # your EAP 7.4 installation
export EAP81=/path/to/jboss-eap-8.1    # your EAP 8.1 installation
export PROJECT=/path/to/this-repo       # where you cloned this project
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

## Step 2 — Start EAP 7.4

```bash
"$EAP80/bin/standalone.sh" -c standalone-full.xml -b 0.0.0.0 &
```

> **`-c standalone-full.xml` is required.** The default `standalone.xml` does not
> include the JMS/messaging subsystem that `kie-server.war` needs.

Wait ~30 seconds, then confirm the extension loaded:

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
curl -s -u adminUser:admin@Redhat1 \
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

Confirm it started (wait ~5 seconds):

```bash
curl -s -u adminUser:admin@Redhat1 \
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
curl -s -u adminUser:admin@Redhat1 \
  -X POST -H "Content-Type: application/json" -H "Accept: application/json" \
  "http://localhost:8080/kie-server/services/rest/server/containers/instances/loan-container" \
  -d '{"lookup":"KBaseKS_stateful","commands":[
    {"insert":{"object":{"com.example.model.Applicant":{"id":"1","age":20}}}},
    {"insert":{"object":{"com.example.model.LoanApplication":{"applicantId":"1"}}}},
    {"fire-all-rules":{"out-identifier":"fired"}}]}'

# Iteration 2 — age 21 (Approved)
curl -s -u adminUser:admin@Redhat1 \
  -X POST -H "Content-Type: application/json" -H "Accept: application/json" \
  "http://localhost:8080/kie-server/services/rest/server/containers/instances/loan-container" \
  -d '{"lookup":"KBaseKS_stateful","commands":[
    {"insert":{"object":{"com.example.model.Applicant":{"id":"2","age":21}}}},
    {"insert":{"object":{"com.example.model.LoanApplication":{"applicantId":"2"}}}},
    {"fire-all-rules":{"out-identifier":"fired"}}]}'

# Iteration 3 — age 35 (Approved)
curl -s -u adminUser:admin@Redhat1 \
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
curl -s -u adminUser:admin@Redhat1 \
  "http://localhost:8080/kie-server/services/rest/server/containers/instances/loan-container/ksession/marshal/KBaseKS/KBaseKS_stateful"
# Expected: Saved loan-container/KBaseKS/KBaseKS_stateful — 6 facts
```

Confirm the snapshot file is on disk:

```bash
ls -lh "$EAP80/standalone/data/kie-snapshots/snapshot-loan-container-KBaseKS-KBaseKS_stateful.ser"
```

---

## Step 7 — Stop EAP 7.4

```bash
"$EAP80/bin/jboss-cli.sh" --connect --command=':shutdown'
```

---

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

Both EAP instances run on the same machine so EAP 8.1 must use a different port.
`-Djboss.socket.binding.port-offset=100` shifts all ports by 100
(HTTP `8080 → 8180`, management `9990 → 10090`).

```bash
"$EAP81/bin/standalone.sh" \
  -c standalone-full.xml -b 0.0.0.0 \
  -Djboss.socket.binding.port-offset=100 &
```

Wait ~30 seconds, then confirm the extension loaded:

```bash
grep "SessionMarshal extension initialized" "$EAP81/standalone/log/server.log"
# Expected: SessionMarshal extension initialized. Snapshot directory: .../standalone/data/kie-snapshots
```

---

## Step 10 — Install the KJAR into the v8.1 KIE repo

```bash
KIE_REPO_81="$EAP81/repositories/kie/global"
mkdir -p "$KIE_REPO_81/com/example/loan-kjar/1.0.0"
cp "$PROJECT/loan-kjar/target/loan-kjar.jar" "$KIE_REPO_81/com/example/loan-kjar/1.0.0/"
```

---

## Step 11 — Deploy the container on v8.1

> **Note:** The KIE Server REST path changed between EAP versions:
> - EAP 7.4: `/kie-server/services/rest/server/...`
> - EAP 8.1: `/kie-server/rest/server/...`

```bash
curl -s -u adminUser:admin@Redhat1 \
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

---

## Step 12 — Verify the migration

Insert applicant 4. The engine already knows applicants 1–3 — it must fire exactly once.

```bash
curl -s -u adminUser:admin@Redhat1 \
  -X POST -H "Content-Type: application/json" -H "Accept: application/json" \
  "http://localhost:8180/kie-server/rest/server/containers/instances/loan-container" \
  -d '{"lookup":"KBaseKS_stateful","commands":[
    {"insert":{"object":{"com.example.model.Applicant":{"id":"4","age":17}}}},
    {"insert":{"object":{"com.example.model.LoanApplication":{"applicantId":"4"}}}},
    {"fire-all-rules":{"out-identifier":"fired"}}]}'
```

| `fired` | Result |
|---|---|
| **1** ✅ | PASS — session migrated successfully from v8.0 to v8.1 |
| **4** ❌ | FAIL — session was empty, snapshot was not restored |

---

## Troubleshooting

| Symptom | Fix |
|---|---|
| `fired = 4` on v8.0 | Snapshot file missing — check `$EAP80/standalone/data/kie-snapshots/` |
| `fired = 4` on v8.1 | Snapshot not copied — redo Step 8 |
| Container stays `CREATING` on v8.1 | KJAR not in v8.1 KIE repo — redo Step 10 |
| Extension endpoint 404 | Extension JAR not in WAR — redo Step 1 |
| EAP starts with errors (MDB failure) | Must use `-c standalone-full.xml`, not default `standalone.xml` |
| EAP 8.1 fails to start (address in use) | EAP 7.4 still running — stop it first (Step 7), or confirm `--port-offset=100` |
| `BUILD FAILURE` — artifact not found | Pass `-s /path/to/settings.xml` pointing to your BAMOE Maven repo |
