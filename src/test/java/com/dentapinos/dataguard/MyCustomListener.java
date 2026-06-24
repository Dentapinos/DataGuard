package com.dentapinos.dataguard;

import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

public class MyCustomListener implements TestExecutionListener {

    @Override
    public void testPlanExecutionStarted(TestPlan testPlan) {
        System.out.println("🚀 Запуск тест-планов: " + testPlan.getRoots().size() + " корневых элементов");
    }

    @Override
    public void executionStarted(TestIdentifier testIdentifier) {
        System.out.println("🧪 Запуск теста: " + testIdentifier.getDisplayName() +
                " (" + testIdentifier.getLegacyReportingName() + ")");
    }

    @Override
    public void executionFinished(TestIdentifier testIdentifier, TestExecutionResult result) {
        String status = switch (result.getStatus()) {
            case SUCCESSFUL -> "✅";
            case ABORTED -> "⚠️";
            case FAILED -> "❌";
        };
        System.out.println(status + " " + testIdentifier.getDisplayName() + " → " + result.getStatus());

        result.getThrowable().ifPresent(t -> {
            System.err.println("Ошибка: " + t.getMessage());
            t.printStackTrace(System.err);
        });
    }

    @Override
    public void testPlanExecutionFinished(TestPlan testPlan) {
        System.out.println("🏁 Тест-план завершён");
    }
}