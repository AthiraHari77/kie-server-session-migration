package com.example.ks.extension;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.kie.api.KieServices;
import org.kie.api.marshalling.Marshaller;
import org.kie.api.runtime.CommandExecutor;
import org.kie.api.runtime.KieSession;
import org.kie.server.services.api.KieContainerInstance;
import org.kie.server.services.api.KieServerExtension;
import org.kie.server.services.api.KieServerRegistry;
import org.kie.server.services.api.SupportedTransports;
import org.kie.server.services.impl.KieServerImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * KIE Server extension that automatically saves and restores stateful KIE sessions
 * across container deployments and server restarts.
 *
 * <p>This class implements the lifecycle hooks only ({@link KieServerExtension}).
 * REST endpoint registration is handled separately by
 * {@link SessionMarshalAppComponents}, which implements
 * {@code KieServerApplicationComponentsService} </p>
 *
 * <p>Two SPI registrations are therefore required:</p>
 * <pre>
 *   META-INF/services/org.kie.server.services.api.KieServerExtension
 *       → com.example.ks.extension.SessionMarshalExtension
 *
 *   META-INF/services/org.kie.server.services.api.KieServerApplicationComponentsService
 *       → com.example.ks.extension.SessionMarshalAppComponents
 * </pre>
 */
public class SessionMarshalExtension implements KieServerExtension {

    private static final Logger log = LoggerFactory.getLogger(SessionMarshalExtension.class);
    public static final String EXTENSION_NAME = "SessionMarshal";

    /**
     * System property name for the snapshot directory.
     * MUST be set before starting the server:
     *   -Dkie.session.snapshot.dir=/your/path
     *
     * Why this is the only resolution path:
     *   Using a container-specific property such as jboss.server.data.dir makes
     *   the extension non-portable — it silently puts snapshots in different
     *   locations on Tomcat, WebSphere, WildFly, etc.  An explicit operator-
     *   provided path is the only reliable, portable approach.
     *
     * Why not /tmp:
     *   /tmp is cleared on reboot — snapshots written there are lost on restart,
     *   which defeats the purpose of persisting session state across restarts.
     *   /tmp is also not writable by the EAP service account on hardened servers.
     *
     * Resolved lazily in init() so the system property is read after EAP has
     * finished its own initialisation, not at static field initialisation time.
     */
    static final String SNAPSHOT_DIR_PROP = "kie.session.snapshot.dir";

    private String snapshotDir;   // resolved in init(), never static
    private KieServerRegistry registry;
    private boolean initialized;

    // ── KieServerExtension lifecycle ──────────────────────────────────────────

    @Override public boolean isInitialized()  { return initialized; }
    @Override public boolean isActive()        { return true; }
    @Override public Integer getStartOrder()   { return 20; }
    @Override public String  getExtensionName(){ return EXTENSION_NAME; }
    @Override public String  getImplementedCapability() { return "BRM-MARSHAL"; }
    @Override public List<Object> getServices()         { return new ArrayList<>(); }
    @Override public String toString() { return EXTENSION_NAME + " KIE Server extension"; }

    @Override
    public void init(KieServerImpl kieServer, KieServerRegistry registry) {
        this.registry = registry;

        // Resolve the snapshot directory from the explicit system property only.
        // No container-specific fallback (jboss.server.data.dir etc.) — using one
        // would silently break portability across EAP, Tomcat, WebSphere, etc.
        snapshotDir = System.getProperty(SNAPSHOT_DIR_PROP);
        if (snapshotDir == null || snapshotDir.isBlank()) {
            log.warn("{} extension: '{}' system property is not set. " +
                     "Set -D{}=/your/path before starting the server. " +
                     "Session save/restore will be DISABLED until the property is present.",
                    EXTENSION_NAME, SNAPSHOT_DIR_PROP, SNAPSHOT_DIR_PROP);
            snapshotDir = null;   // explicit null → guards in saveSession / restoreSession
        }

        initialized = true;
        log.info("{} extension initialized. Snapshot directory: {}",
                EXTENSION_NAME, snapshotDir != null ? snapshotDir : "<NOT SET — save/restore disabled>");
    }

    @Override
    public void destroy(KieServerImpl kieServer, KieServerRegistry registry) {
        // Server-level shutdown — nothing to do here; individual containers are
        // already saved via disposeContainer() which EAP calls for each one first.
    }

    /**
     * Called by KIE Server after every container is deployed (first deployment,
     * server restart, container update). Restores a snapshot for every KBase/session
     * combination that has a matching file on disk.
     *
     * Iterates all KBases and all session names within each KBase so that KJARs
     * with multiple packages, multiple KBases, or multiple sessions per KBase are
     * all handled correctly.
     *
     * Snapshot file name format:
     *   snapshot-{containerId}-{kbaseName}-{sessionName}.ser
     *
     * Including the KBase name prevents a collision when two different KBases
     * declare a session with the same name.
     */
    @Override
    public void createContainer(String id, KieContainerInstance container, Map<String, Object> parameters) {
        if (snapshotDir == null) {
            log.warn("[createContainer] Skipping restore for container={} — '{}' not configured",
                    id, SNAPSHOT_DIR_PROP);
            return;
        }
        forEachSession(id, container, (kbaseName, sessionName, session) -> {
            File snapshot = snapshotFile(id, kbaseName, sessionName);
            if (!snapshot.exists()) {
                log.info("[createContainer] No snapshot for container={} kbase={} session={} — starting empty",
                        id, kbaseName, sessionName);
                return;
            }
            restoreSession(id, kbaseName, sessionName, session, snapshot);
        });
    }

    /**
     * Called by KIE Server before a container is disposed — this covers:
     *   • Operator calls DELETE /containers/{id}
     *   • Server is shutting down (EAP calls disposeContainer for each container
     *     in reverse start order before the JVM exits)
     *   • Container update (old version disposed before new version is created)
     *
     * Automatically saves every KBase/session combination to its snapshot file
     * so the state is available for the next createContainer() call.
     */
    @Override
    public void disposeContainer(String id, KieContainerInstance container, Map<String, Object> parameters) {
        log.info("[disposeContainer] Auto-saving all sessions for container={}", id);
        forEachSession(id, container, (kbaseName, sessionName, session) ->
                saveSession(id, kbaseName, sessionName, session, "disposeContainer"));
    }

    @Override
    public void updateContainer(String id, KieContainerInstance container, Map<String, Object> parameters) {
    }

    @Override
    public boolean isUpdateContainerAllowed(String id, KieContainerInstance container,
                                            Map<String, Object> parameters) {
        return true;
    }

    /**
     * REST components are registered by {@link SessionMarshalAppComponents} via the
     * {@code KieServerApplicationComponentsService} SPI
     * This method therefore returns an empty list; it is not the correct place to register REST resources.
     */
    @Override
    public List<Object> getAppComponents(SupportedTransports type) {
        return Collections.emptyList();
    }

    @Override
    public <T> T getAppComponents(Class<T> serviceType) {
        return null;
    }

    // ── Shared helpers (package-private so SessionMarshalAppComponents can call them) ──

    /**
     * Iterates every KBase declared in the container's kmodule.xml, and within
     * each KBase every session name. Calls the provided action for each
     * (kbaseName, sessionName, liveSession) triple that can be resolved.
     *
     * Handles any number of packages, KBases, and sessions — nothing is hardcoded.
     */
    void forEachSession(String containerId, KieContainerInstance container,
                        SessionAction action) {
        Collection<String> kbaseNames = container.getKieContainer().getKieBaseNames();
        if (kbaseNames.isEmpty()) {
            log.info("No KBases found in container {}", containerId);
            return;
        }

        for (String kbaseName : kbaseNames) {
            Collection<String> sessionNames = container.getKieContainer()
                    .getKieSessionNamesInKieBase(kbaseName);

            for (String sessionName : sessionNames) {
                CommandExecutor executor = registry.getKieSessionLookupManager()
                        .lookup(sessionName, container, registry);

                if (!(executor instanceof KieSession)) {
                    log.warn("container={} kbase={} session={} — lookup did not return a KieSession, skipping",
                            containerId, kbaseName, sessionName);
                    continue;
                }

                try {
                    action.accept(kbaseName, sessionName, (KieSession) executor);
                } catch (Exception e) {
                    log.error("container={} kbase={} session={} — action failed",
                            containerId, kbaseName, sessionName, e);
                }
            }
        }
    }

    /**
     * Saves a single KieSession to its snapshot file using an atomic write:
     * write to a .tmp file first, then rename to the final path so the restore
     * side never sees a half-written file.
     */
    void saveSession(String containerId, String kbaseName, String sessionName,
                     KieSession session, String caller) {
        if (snapshotDir == null) {
            log.warn("[{}] Skipping save for container={} kbase={} session={} — '{}' not configured",
                    caller, containerId, kbaseName, sessionName, SNAPSHOT_DIR_PROP);
            return;
        }
        File target = snapshotFile(containerId, kbaseName, sessionName);
        File tmp    = new File(target.getParent(), target.getName() + ".tmp");
        target.getParentFile().mkdirs();

        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            Marshaller marshaller = KieServices.get().getMarshallers()
                    .newMarshaller(session.getKieBase());
            marshaller.marshall(fos, session);
        } catch (IOException e) {
            log.error("[{}] Save failed for container={} kbase={} session={}",
                    caller, containerId, kbaseName, sessionName, e);
            tmp.delete();
            return;
        }

        try {
            Files.move(tmp.toPath(), target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.error("[{}] Atomic rename failed: {} → {}", caller, tmp, target, e);
            return;
        }

        log.info("[{}] Saved container={} kbase={} session={} — {} facts → {}",
                caller, containerId, kbaseName, sessionName, session.getFactCount(), target);
    }

    /**
     * Restores a single KieSession from its snapshot file in-place — the existing
     * session object is kept; only its working memory is replaced.
     */
    private void restoreSession(String containerId, String kbaseName, String sessionName,
                                KieSession session, File snapshot) {
        // snapshotDir null-check is performed in createContainer() before calling us
        try (FileInputStream fis = new FileInputStream(snapshot)) {
            Marshaller marshaller = KieServices.get().getMarshallers()
                    .newMarshaller(session.getKieBase());
            marshaller.unmarshall(fis, session);
            log.info("[createContainer] Restored container={} kbase={} session={} — {} facts from {}",
                    containerId, kbaseName, sessionName, session.getFactCount(), snapshot);
        } catch (Exception e) {
            log.error("[createContainer] Restore failed for container={} kbase={} session={}",
                    containerId, kbaseName, sessionName, e);
        }
    }

    /**
     * Builds the canonical snapshot file path for a given container/kbase/session triple.
     *
     * Format: {snapshotDir}/snapshot-{containerId}-{kbaseName}-{sessionName}.ser
     *
     * Including kbaseName in the filename is essential when two KBases in the same
     * container declare sessions with identical names — without it the files would
     * collide and one snapshot would silently overwrite the other.
     */
    File snapshotFile(String containerId, String kbaseName, String sessionName) {
        return new File(snapshotDir,
                "snapshot-" + containerId + "-" + kbaseName + "-" + sessionName + ".ser");
    }

    /** Tri-consumer used by forEachSession to keep the iteration logic in one place. */
    @FunctionalInterface
    interface SessionAction {
        void accept(String kbaseName, String sessionName, KieSession session) throws Exception;
    }
}
