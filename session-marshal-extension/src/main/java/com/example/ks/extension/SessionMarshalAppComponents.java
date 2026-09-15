package com.example.ks.extension;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.core.Response;

import org.kie.api.runtime.CommandExecutor;
import org.kie.api.runtime.KieSession;
import org.kie.server.services.api.KieContainerInstance;
import org.kie.server.services.api.KieServerApplicationComponentsService;
import org.kie.server.services.api.KieServerRegistry;
import org.kie.server.services.api.SupportedTransports;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registers the custom REST endpoint for on-demand session marshalling with
 * KIE Server via the {@code KieServerApplicationComponentsService} SPI.
 *
 * <p>The {@code getAppComponents} method is called once per enabled extension.
 * The {@code OWNER_EXTENSION} guard ensures we only respond to the call from
 * the {@code Drools} extension, which supplies the {@link KieServerRegistry}
 * service we need.</p>
 *
 * <p>Registered via:
 * {@code META-INF/services/org.kie.server.services.api.KieServerApplicationComponentsService}</p>
 *
 * <h3>Endpoint base path</h3>
 * <pre>
 *   GET server/containers/instances/{containerId}/ksession/marshal
 *       → save all sessions in the container
 *
 *   GET server/containers/instances/{containerId}/ksession/marshal/{kbaseName}/{sessionName}
 *       → save one specific session
 * </pre>
 *
 * Full URL example (BAMOE 8.0 / EAP 7.4):
 * <pre>
 *   http://localhost:8080/kie-server/services/rest/server/containers/instances/loan-container/ksession/marshal
 * </pre>
 */
public class SessionMarshalAppComponents implements KieServerApplicationComponentsService {

    /**
     * The extension whose services we consume.
     */
    private static final String OWNER_EXTENSION = "Drools";

    /**
     * Called once per enabled KIE Server extension. Returns the REST resource
     * only when called by the Drools extension (which owns KieServerRegistry).
     */
    @Override
    public Collection<Object> getAppComponents(String extension,
                                               SupportedTransports type,
                                               Object... services) {
        // Do not respond to calls from extensions other than the owner:
        if (!OWNER_EXTENSION.equals(extension)) {
            return Collections.emptyList();
        }

        KieServerRegistry registry = null;
        for (Object svc : services) {
            if (svc instanceof KieServerRegistry) {
                registry = (KieServerRegistry) svc;
                break;
            }
        }

        if (registry == null || !SupportedTransports.REST.equals(type)) {
            return Collections.emptyList();
        }

        List<Object> components = new ArrayList<>();
        components.add(new MarshalRestResource(registry));
        return components;
    }

    // ── Custom REST resource ──────────────────────────────────────────────────

    /**
     * JAX-RS resource that exposes on-demand session marshalling.
     *
     * Base path :
     *   server/containers/instances/{containerId}/ksession/...
     *
     * This sits under the KIE Server REST root so that it is consistent with
     * the other container-scoped endpoints already present on the server.
     */
    @Path("server/containers/instances/{containerId}/ksession")
    public static class MarshalRestResource {

        private static final Logger log = LoggerFactory.getLogger(MarshalRestResource.class);

        private final KieServerRegistry registry;

        MarshalRestResource(KieServerRegistry registry) {
            this.registry = registry;
        }

        /**
         * Saves all sessions in the container to disk.
         *
         * GET server/containers/instances/{containerId}/ksession/marshal
         */
        @GET
        @Path("/marshal")
        public Response marshalAll(@PathParam("containerId") String containerId) {
            KieContainerInstance container = registry.getContainer(containerId);
            if (container == null) {
                return Response.status(404).entity("No such container: " + containerId).build();
            }

            // Obtain the lifecycle extension to reuse its snapshot path logic and saveSession().
            SessionMarshalExtension ext = getExtension();
            if (ext == null) {
                return Response.status(503).entity("SessionMarshal extension not available").build();
            }

            StringBuilder report = new StringBuilder();
            ext.forEachSession(containerId, container, (kbaseName, sessionName, session) -> {
                ext.saveSession(containerId, kbaseName, sessionName, session, "REST-marshalAll");
                report.append("Saved ").append(containerId).append("/").append(kbaseName)
                      .append("/").append(sessionName)
                      .append(" — ").append(session.getFactCount()).append(" facts\n");
            });

            String body = report.toString().trim();
            return body.isEmpty()
                    ? Response.status(404).entity("No sessions in container: " + containerId).build()
                    : Response.ok(body).build();
        }

        /**
         * Saves one specific session to disk.
         *
         * GET server/containers/instances/{containerId}/ksession/marshal/{kbaseName}/{sessionName}
         */
        @GET
        @Path("/marshal/{kbaseName}/{sessionName}")
        public Response marshalOne(@PathParam("containerId") String containerId,
                                   @PathParam("kbaseName")   String kbaseName,
                                   @PathParam("sessionName") String sessionName) {
            KieContainerInstance container = registry.getContainer(containerId);
            if (container == null) {
                return Response.status(404).entity("No such container: " + containerId).build();
            }

            SessionMarshalExtension ext = getExtension();
            if (ext == null) {
                return Response.status(503).entity("SessionMarshal extension not available").build();
            }

            CommandExecutor executor = registry.getKieSessionLookupManager()
                    .lookup(sessionName, container, registry);
            if (!(executor instanceof KieSession)) {
                return Response.status(404)
                        .entity("Session not found or not a KieSession: " + sessionName).build();
            }

            KieSession session = (KieSession) executor;
            ext.saveSession(containerId, kbaseName, sessionName, session, "REST-marshalOne");

            String msg = "Saved " + containerId + "/" + kbaseName + "/" + sessionName
                    + " — " + session.getFactCount() + " facts → "
                    + ext.snapshotFile(containerId, kbaseName, sessionName).getAbsolutePath();
            log.info("[REST] {}", msg);
            return Response.ok(msg).build();
        }

        /**
         * Looks up the lifecycle extension instance from the server registry so
         * this REST resource can delegate to its shared saveSession() and
         * snapshotFile() logic without duplicating them.
         */
        private SessionMarshalExtension getExtension() {
            // KieServerRegistry.getServerExtension() returns the registered extension by name.
            org.kie.server.services.api.KieServerExtension ext =
                    registry.getServerExtension(SessionMarshalExtension.EXTENSION_NAME);
            return (ext instanceof SessionMarshalExtension) ? (SessionMarshalExtension) ext : null;
        }
    }
}
