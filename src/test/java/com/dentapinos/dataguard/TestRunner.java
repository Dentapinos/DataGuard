package com.dentapinos.dataguard;

import org.junit.platform.engine.discovery.ClassNameFilter;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.engine.discovery.PackageNameFilter;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.TestPlan;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

public class TestRunner {

    public static void main(String[] args) {
        // all | unit | it
        String mode = args.length > 0 ? args[0] : "all";

        LauncherDiscoveryRequestBuilder builder = LauncherDiscoveryRequestBuilder.request()
                .selectors(
                        DiscoverySelectors.selectPackage("com.dentapinos.dataguard")
                );

        // фильтры по имени класса: *Test / *IT
        switch (mode) {
            case "unit" -> builder
                    .filters(
                            PackageNameFilter.includePackageNames("com.dentapinos.dataguard"),
                            ClassNameFilter.includeClassNamePatterns(".*Test")
                    );
            case "it" -> builder
                    .filters(
                            PackageNameFilter.includePackageNames("com.dentapinos.dataguard"),
                            ClassNameFilter.includeClassNamePatterns(".*IT")
                    );
            case "all" -> {
                // без фильтра по имени — все тесты
            }
            default -> {
                System.err.println("Unknown mode: " + mode + " (expected: all|unit|it)");
                System.exit(1);
            }
        }

        LauncherDiscoveryRequest request = builder.build();

        Launcher launcher = LauncherFactory.create();

        // только наш listener, без TerminalTestExecutionListener
        launcher.registerTestExecutionListeners(new MyCustomListener());

        TestPlan testPlan = launcher.discover(request);
        launcher.execute(testPlan);

        System.out.println("Tests finished. Mode = " + mode);
    }
}