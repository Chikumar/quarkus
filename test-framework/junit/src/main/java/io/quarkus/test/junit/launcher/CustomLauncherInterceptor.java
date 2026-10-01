package io.quarkus.test.junit.launcher;

import java.io.IOException;
import java.net.URL;
import java.util.Enumeration;
import java.util.Optional;

import org.jboss.logging.Logger;
import org.junit.jupiter.api.ClassOrderer;
import org.junit.platform.launcher.LauncherDiscoveryListener;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestPlan;

import io.quarkus.test.config.QuarkusClassOrderer;
import io.quarkus.test.junit.classloading.FacadeClassLoader;
import io.quarkus.test.junit.util.QuarkusTestProfileAwareClassOrderer;

public class CustomLauncherInterceptor
        implements LauncherDiscoveryListener, LauncherSessionListener, TestExecutionListener {

    private static final Logger LOG = Logger.getLogger(CustomLauncherInterceptor.class);

    private static final Class<? extends ClassOrderer> DESIRED_CLASS_ORDERER = QuarkusTestProfileAwareClassOrderer.class;
    private static final Class<? extends ClassOrderer> CONFIG_SETTING_DESIRED_CLASS_ORDERER = QuarkusClassOrderer.class;
    private static final String ORDERER_CHECK_DISABLE_PROPERTY = "quarkus.test.disable-orderer-check";

    private static FacadeClassLoader facadeLoader = null;
    // Also use a static variable to store a 'first' starting state that we can reset to
    private static ClassLoader origCl = null;
    private static boolean discoveryStarted = false;

    public CustomLauncherInterceptor() {
    }

    private static boolean isProductionModeTests() {
        // We're too early for config to be available, so just check the system props
        return System.getProperty("prod.mode.tests") != null;
    }

    @Override
    public void launcherSessionOpened(LauncherSession session) {
        /*
         * For gradle, test class loading happens fairly shortly after this is called,
         * before the formal discovery phase. So we need to intercept the TCCL.
         *
         * However, the Eclipse runner calls this twice, and the second invocation happens after discovery,
         * which means there is no one to unset the TCCL. That breaks integration tests, so we
         * need to add an ugly guard to not adjust the TCCL the second time round in that scenario.
         *
         * There is a similar issue with continuous testing, causing us to go into tests with the FacadeClassLoader set.
         * That's not the right CL, so in those cases skip setting it.
         *
         * We do not do any classloading dance for prod mode tests.
         */
        boolean shouldSetTCCL = !discoveryStarted;

        if (!isProductionModeTests() && shouldSetTCCL) {
            actuallyIntercept();
        }

    }

    private void actuallyIntercept() {
        if (origCl == null) {
            origCl = Thread.currentThread()
                    .getContextClassLoader();
        }
        initializeFacadeClassLoader();
        adjustContextClassLoader();

        // It's tempting to tidy up in a finally block by resetting the TCCL, but the gradle tests
        // do discovery 'between' invocation blocks, and outside the main

    }

    // Make a facade classloader if needed, so that we can close it at the end of the launcher session
    private void initializeFacadeClassLoader() {
        ClassLoader currentCl = Thread.currentThread().getContextClassLoader();
        // Be aware, this method might be called more than once, for different kinds of invocations; especially for Gradle executions, the executions could happen before the TCCL gets constructed and set by JUnitTestRunner
        // We might not be in the same classloader as the Facade ClassLoader, so use a name comparison instead of an instanceof
        if (currentCl == null
                || (currentCl != facadeLoader && !currentCl.getClass().getName().equals(FacadeClassLoader.class.getName()))) {

            // We don't ever want more than one FacadeClassLoader active, especially since config gets initialised on it.
            // The gradle test execution can make more than one, perhaps because of its threading model.
            if (facadeLoader == null) {
                facadeLoader = new FacadeClassLoader(currentCl);
            }
        }

    }

    @Override
    public void launcherDiscoveryStarted(LauncherDiscoveryRequest request) {
        discoveryStarted = true;
        // If anything comes through this method for which there are non-null classloaders on the selectors, that will bypass our classloading
        // To check that case, the code would be something like this. We could detect and warn early, and possibly even filter that test out, but that's not necessarily a better UX than failing later
        // request.getSelectorsByType(ClassSelector.class).stream().map(ClassSelector::getClassLoader) ... and then check for non-emptiness on that field

        // Do not do any classloading dance for prod mode tests;
        if (!isProductionModeTests()) {
            initializeFacadeClassLoader();
            adjustContextClassLoader();
            // Test classes get loaded (and Quarkus applications built for them) during discovery, before JUnit applies these filters;
            // let the facade classloader know about them so it does not build applications for classes JUnit is going to drop anyway
            if (facadeLoader != null) {
                facadeLoader.setPostDiscoveryFilters(request.getPostDiscoveryFilters());
            }

            // we need to ensure that the Fork-Join pool will use our thread factory, otherwise the TCCL
            // of the threads could be wrong
            System.setProperty("java.util.concurrent.ForkJoinPool.common.threadFactory",
                    "io.quarkus.bootstrap.forkjoin.QuarkusForkJoinWorkerThreadFactory");
        }
    }

    private void adjustContextClassLoader() {
        ClassLoader currentCl = Thread.currentThread().getContextClassLoader();
        // Be aware, this method might be called more than once, for different kinds of invocations; especially for Gradle executions, the executions could happen before the TCCL gets constructed and set by JUnitTestRunner
        // We might not be in the same classloader as the Facade ClassLoader, so use a name comparison instead of an instanceof
        if (currentCl == null
                || (currentCl != facadeLoader && !currentCl.getClass().getName().equals(FacadeClassLoader.class.getName()))) {
            Thread.currentThread().setContextClassLoader(facadeLoader);
        }
    }

    @Override
    public void launcherDiscoveryFinished(LauncherDiscoveryRequest request) {
        if (!isProductionModeTests()) {
            // We need to support two somewhat incompatible scenarios.
            // If there are user extensions present which implement `ExecutionCondition`, and they call config in `evaluateExecutionCondition`,
            // they need the TCCL to be right for reading config (that is, the app classloader)
            // On the other hand, if the QuarkusTestExtension is registered by a service loader mechanism, it gets loaded after the discovery phase finishes,
            // so needs the TCCL to still be the facade classloader.
            // This compromise does mean you can't use the service loader mechanism to avoid having to use `@QuarkusTest` and also use Quarkus config in your own test extensions, but that combination is very unlikely.
            if (facadeLoader != null && !facadeLoader.isServiceLoaderMechanism()) {
                // Do not close the facade loader at this stage, because discovery finished may be called several times within a single run
                // Ideally we would reset to what the TCCL was when we started discovery, but we can't,
                // because the intercept method will have set something before the discovery start is triggered.
                // So, rather annoyingly and clumsily, reset the TCCL to what it was when the first interception happened
                Thread.currentThread().setContextClassLoader(origCl);

            }

            // Allow users to disable this check if they know what they're doing
            if (System.getProperty(ORDERER_CHECK_DISABLE_PROPERTY) != null) {
                LOG.debugf("Class orderer validation disabled via system property %s", ORDERER_CHECK_DISABLE_PROPERTY);
                return;
            }

            Optional<String> orderer = request.getConfigurationParameters().get("junit.jupiter.testclass.order.default");

            if (orderer.isEmpty() || !(orderer.get()
                    .equals(DESIRED_CLASS_ORDERER.getName())
                    || orderer.get().equals(CONFIG_SETTING_DESIRED_CLASS_ORDERER.getName()))) {
                if (facadeLoader != null && facadeLoader.hasMultipleClassLoaders()) {
                    // Check if the Quarkus junit-platform.properties is on the classpath
                    boolean quarkusPropertiesFound = isQuarkusJunitPropertiesOnClasspath();

                    if (quarkusPropertiesFound && orderer.isEmpty()) {
                        // The Quarkus properties file is on the classpath but JUnit didn't load it
                        // This might be a timing issue or classloader issue - log a warning but don't fail
                        LOG.warnf(
                                "Multiple test profiles detected but junit.jupiter.testclass.order.default is not set. "
                                        + "However, the Quarkus junit-platform.properties file was found on the classpath. "
                                        + "If you encounter test failures with 'corrupted application' errors, please add "
                                        + "junit.jupiter.testclass.order.default=%s to your project's junit-platform.properties file. "
                                        + "To suppress this warning, set the system property -D%s=true",
                                DESIRED_CLASS_ORDERER.getName(), ORDERER_CHECK_DISABLE_PROPERTY);
                    } else {
                        String message = getFailureMessageForJUnitMisconfiguration(orderer, quarkusPropertiesFound);
                        throw new IllegalStateException(message);
                    }
                }
            }
        }
    }

    /**
     * Check if the Quarkus junit-platform.properties file is on the classpath.
     */
    private static boolean isQuarkusJunitPropertiesOnClasspath() {
        try {
            ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
            if (classLoader == null) {
                classLoader = CustomLauncherInterceptor.class.getClassLoader();
            }
            Enumeration<URL> resources = classLoader.getResources("junit-platform.properties");
            while (resources.hasMoreElements()) {
                URL url = resources.nextElement();
                String urlString = url.toString();
                // Check if this is the Quarkus test framework's properties file
                if (urlString.contains("quarkus-junit") || urlString.contains("io/quarkus/test")) {
                    return true;
                }
            }
        } catch (IOException e) {
            LOG.debugf(e, "Failed to check for Quarkus junit-platform.properties on classpath");
        }
        return false;
    }

    private static String getFailureMessageForJUnitMisconfiguration(Optional<String> orderer, boolean quarkusPropertiesFound) {
        String generalExplanation = """
                Critical failure. Quarkus tests would fail with corrupted application errors.
                The reason is that they would not run in the right order, because the Quarkus JUnit configuration has been overridden.
                When there are multiple test profiles or resources, Quarkus uses a JUnit ClassOrderer to sort tests so that test with the same profile run one after each other.
                Running tests with a different sorting risks tests running on an application that has been cleaned up.
                """;

        String message;
        if (orderer.isPresent()) {
            // A custom orderer is configured, which is overriding the Quarkus orderer
            message = String.format(
                    "%sA custom class orderer '%s' has been configured which overrides the Quarkus required ordering.\n"
                            + "To preserve your custom ordering while allowing Quarkus to group tests by profile, you can:\n"
                            + "1. Use the Quarkus secondary orderer configuration: Add quarkus.test.class-orderer=%s to your application.properties\n"
                            + "2. Or, if you're certain your tests don't need profile-based grouping, disable this check with system property: -D%s=true\n"
                            + "3. Or, replace the orderer with %s in your junit-platform.properties file",
                    generalExplanation, orderer.get(), orderer.get(), ORDERER_CHECK_DISABLE_PROPERTY,
                    DESIRED_CLASS_ORDERER.getName());
        } else {
            // No orderer is configured
            if (quarkusPropertiesFound) {
                message = String.format(
                        """
                                %sThe Quarkus junit-platform.properties file was found on the classpath, but JUnit did not load the class orderer configuration from it.
                                This usually happens when another junit-platform.properties file earlier in the classpath overrides it.
                                To fix this:
                                1. Add junit.jupiter.testclass.order.default=%s to your project's junit-platform.properties file (if you have one)
                                2. Or, remove any junit-platform.properties files from your project and let Quarkus provide the configuration
                                3. Or, if you're certain your tests don't need profile-based grouping, disable this check with system property: -D%s=true""",
                        generalExplanation, DESIRED_CLASS_ORDERER.getName(), ORDERER_CHECK_DISABLE_PROPERTY);
            } else {
                message = String.format(
                        """
                                %sThe Quarkus junit-platform.properties file was NOT found on the classpath.
                                This suggests a dependency or classpath issue with the Quarkus test framework.
                                To fix this:
                                1. Ensure you have the correct Quarkus test framework dependency (io.quarkus:quarkus-junit5)
                                2. Or, add junit.jupiter.testclass.order.default=%s to a junit-platform.properties file in your test resources
                                3. Or, if you're certain your tests don't need profile-based grouping, disable this check with system property: -D%s=true""",
                        generalExplanation, DESIRED_CLASS_ORDERER.getName(), ORDERER_CHECK_DISABLE_PROPERTY);
            }
        }
        return message;
    }

    @Override
    public void launcherSessionClosed(LauncherSession session) {
        clearContextClassloader();
    }

    private static void clearContextClassloader() {
        try {
            // Tidy up classloaders we created, but not ones created upstream
            // Also make sure to reset the TCCL so we don't leave a closed classloader on the thread
            if (facadeLoader != null) {

                // Reset the TCCL if it's one we set, but not otherwise
                if (Thread.currentThread().getContextClassLoader() == facadeLoader) {
                    Thread.currentThread().setContextClassLoader(origCl);
                }

                facadeLoader.close();
                facadeLoader = null;

            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to close custom classloader", e);
        }
    }

    /**
     * Called when the execution of the {@link TestPlan} has started,
     * <em>before</em> any test has been executed.
     *
     * <p>
     * Called from the same thread as {@link #testPlanExecutionFinished(TestPlan)}.
     *
     * In continuous testing, the test plan listener seems not to be called, perhaps because it passes in its own
     * TestExecutionListener and that overrides what is found with service loading.
     *
     * @param testPlan describes the tree of tests about to be executed
     */
    @Override
    public void testPlanExecutionStarted(TestPlan testPlan) {
        // Do nothing, but have the method here for symmetry :)
    }

    /**
     * Called when the execution of the {@link TestPlan} has finished,
     * <em>after</em> all tests have been executed.
     *
     * <p>
     * Called from the same thread as {@link #testPlanExecutionStarted(TestPlan)}.
     *
     * If tests failed and are rerun by surefire, the same session will be used for all runs, so we need to get rid of the
     * FacadeClassLoader associated with the previous run, since its app will be closed and its classloaders will all be stale.
     *
     * In continuous testing, the test plan listener seems not to be called, perhaps because it passes in its own
     * TestExecutionListener and that overrides what is found with service loading.
     *
     * @param testPlan describes the tree of tests that have been executed
     */
    @Override
    public void testPlanExecutionFinished(TestPlan testPlan) {
        clearContextClassloader();
    }
}
