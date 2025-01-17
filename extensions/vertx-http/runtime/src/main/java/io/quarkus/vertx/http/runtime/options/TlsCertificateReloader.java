package io.quarkus.vertx.http.runtime.options;

import static io.quarkus.vertx.http.runtime.options.HttpServerOptionsUtils.getFileContent;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.Supplier;

import org.jboss.logging.Logger;

import io.quarkus.vertx.http.runtime.ServerSslConfig;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.net.KeyStoreOptions;
import io.vertx.core.net.PemKeyCertOptions;
import io.vertx.core.net.SSLOptions;

/**
 * Utility class to handle TLS certificate reloading.
 */
public class TlsCertificateReloader {

    /**
     * A structure storing the reload tasks.
     */
    private static final List<ReloadCertificateTask> TASKS = new CopyOnWriteArrayList<>();

    private static final Logger LOGGER = Logger.getLogger(TlsCertificateReloader.class);

    public static long initCertReloadingAction(Vertx vertx, HttpServer server,
            HttpServerOptions options, ServerSslConfig configuration) {

        if (options == null) {
            throw new IllegalArgumentException("Unable to configure TLS reloading - The HTTP server options were not provided");
        }
        SSLOptions ssl = options.getSslOptions();
        if (ssl == null) {
            throw new IllegalArgumentException("Unable to configure TLS reloading - TLS/SSL is not enabled on the server");
        }

        long period;
        // Validation
        if (configuration.certificate.reloadPeriod.isPresent()) {
            if (configuration.certificate.reloadPeriod.get().toMillis() < 30_000) {
                throw new IllegalArgumentException(
                        "Unable to configure TLS reloading - The reload period cannot be less than 30 seconds");
            }
            period = configuration.certificate.reloadPeriod.get().toMillis();
        } else {
            return -1;
        }

        Supplier<CompletionStage<Boolean>> task = new Supplier<CompletionStage<Boolean>>() {
            @Override
            public CompletionStage<Boolean> get() {

                Future<Boolean> future = vertx.executeBlocking(new Callable<SSLOptions>() {
                    @Override
                    public SSLOptions call() throws Exception {
                        // We are reading files - must be done on a worker thread.
                        var c = reloadFileContent(ssl, configuration);
                        if (c.equals(ssl)) { // No change, skip the update
                            return null;
                        }
                        return c;
                    }
                }, true)
                        .flatMap(new Function<SSLOptions, Future<Boolean>>() {
                            @Override
                            public Future<Boolean> apply(SSLOptions res) {
                                if (res != null) {
                                    return server.updateSSLOptions(res);
                                } else {
                                    return Future.succeededFuture(false);
                                }
                            }
                        })
                        .onComplete(new Handler<AsyncResult<Boolean>>() {
                            @Override
                            public void handle(AsyncResult<Boolean> ar) {
                                if (ar.failed()) {
                                    LOGGER.error("Unable to reload the TLS certificate, keeping the current one.", ar.cause());
                                } else {
                                    if (ar.result()) {
                                        LOGGER.debug("TLS certificates updated");
                                    }
                                    // Not updated, no change.
                                }
                            }
                        });

                return future.toCompletionStage();
            }
        };

        long id = vertx.setPeriodic(period, new Handler<Long>() {
            @Override
            public void handle(Long id) {
                task.get();
            }
        });

        TASKS.add(new ReloadCertificateTask(id, task));
        return id;
    }

    public static void unschedule(Vertx vertx, long id) {
        vertx.cancelTimer(id);
        for (ReloadCertificateTask task : TASKS) {
            if (task.it == id) {
                TASKS.remove(task);
                break;
            }
        }
    }

    /**
     * Trigger all the reload tasks.
     * This method is <strong>NOT</strong> part of the public API, and is only used for testing purpose.
     *
     * @return a Uni that is completed when all the reload tasks have been executed
     */
    public static CompletionStage<Void> reload() {
        @SuppressWarnings("rawtypes")
        CompletableFuture[] futures = new CompletableFuture[TASKS.size()];
        for (int i = 0; i < TASKS.size(); i++) {
            futures[i] = TASKS.get(i).action().get().toCompletableFuture();
        }

        return CompletableFuture.allOf(futures);
    }

    private static SSLOptions reloadFileContent(SSLOptions ssl, ServerSslConfig configuration) throws IOException {
        var copy = new SSLOptions(ssl);

        final List<Path> keys = new ArrayList<>();
        final List<Path> certificates = new ArrayList<>();

        if (configuration.certificate.keyFiles.isPresent()) {
            keys.addAll(configuration.certificate.keyFiles.get());
        }
        if (configuration.certificate.files.isPresent()) {
            certificates.addAll(configuration.certificate.files.get());
        }

        if (!certificates.isEmpty() && !keys.isEmpty()) {
            List<Buffer> certBuffer = new ArrayList<>();
            List<Buffer> keysBuffer = new ArrayList<>();

            for (Path p : certificates) {
                byte[] cert = getFileContent(p);
                certBuffer.add(Buffer.buffer(cert));
            }
            for (Path p : keys) {
                byte[] key = getFileContent(p);
                keysBuffer.add(Buffer.buffer(key));
            }

            PemKeyCertOptions opts = new PemKeyCertOptions()
                    .setCertValues(certBuffer)
                    .setKeyValues(keysBuffer);
            copy.setKeyCertOptions(opts);
        } else if (configuration.certificate.keyStoreFile.isPresent()) {
            var opts = ((KeyStoreOptions) copy.getKeyCertOptions());
            opts.setValue(Buffer.buffer(getFileContent(configuration.certificate.keyStoreFile.get())));
            copy.setKeyCertOptions(opts);
        }

        if (configuration.certificate.trustStoreFile.isPresent()) {
            var opts = ((KeyStoreOptions) copy.getKeyCertOptions());
            opts.setValue(Buffer.buffer(getFileContent(configuration.certificate.trustStoreFile.get())));
            copy.setTrustOptions(opts);
        }

        return copy;
    }

    static final class ReloadCertificateTask {
        private final long it;
        private final Supplier<CompletionStage<Boolean>> action;

        ReloadCertificateTask(long it, Supplier<CompletionStage<Boolean>> action) {
            this.it = it;
            this.action = action;
        }

        public long it() {
            return it;
        }

        public Supplier<CompletionStage<Boolean>> action() {
            return action;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == this)
                return true;
            if (obj == null || obj.getClass() != this.getClass())
                return false;
            var that = (ReloadCertificateTask) obj;
            return this.it == that.it &&
                    Objects.equals(this.action, that.action);
        }

        @Override
        public int hashCode() {
            return Objects.hash(it, action);
        }

        @Override
        public String toString() {
            return "ReloadCertificateTask[" +
                    "it=" + it + ", " +
                    "action=" + action + ']';
        }

    }
}
