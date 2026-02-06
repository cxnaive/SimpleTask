package dev.user.simpletask.database;

import dev.user.simpletask.SimpleTaskPlugin;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.*;
import java.util.function.Consumer;

public class DatabaseQueue {

    private final SimpleTaskPlugin plugin;
    private final BlockingQueue<DatabaseTask<?>> taskQueue;
    private final ExecutorService executor;
    private volatile boolean running = true;

    public DatabaseQueue(SimpleTaskPlugin plugin) {
        this.plugin = plugin;
        this.taskQueue = new LinkedBlockingQueue<>();
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "SimpleTask-DB-Queue");
            t.setDaemon(true);
            return t;
        });

        startProcessing();
    }

    private void startProcessing() {
        executor.submit(() -> {
            while (running || !taskQueue.isEmpty()) {
                try {
                    DatabaseTask<?> task = taskQueue.poll(100, TimeUnit.MILLISECONDS);
                    if (task != null) {
                        processTask(task);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
    }

    private <T> void processTask(DatabaseTask<T> task) {
        long startTime = System.currentTimeMillis();

        // 队列自动管理连接！使用 try-with-resources
        try (Connection connection = plugin.getDatabaseManager().getConnection()) {
            T result = task.getOperation().execute(connection);
            long duration = System.currentTimeMillis() - startTime;

            // 慢查询检测
            if (duration > 1000) {
                plugin.getLogger().warning("Slow query [" + task.getName() + "] took: " + duration + "ms");
            }

            // 回调到主线程
            if (task.getCallback() != null) {
                plugin.getServer().getGlobalRegionScheduler().execute(plugin, () -> {
                    try {
                        task.getCallback().accept(result);
                    } catch (Exception e) {
                        plugin.getLogger().warning("Database callback failed: " + e.getMessage());
                    }
                });
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Database operation failed [" + task.getName() + "]: " + e.getMessage());

            if (task.getErrorCallback() != null) {
                plugin.getServer().getGlobalRegionScheduler().execute(plugin, () -> {
                    task.getErrorCallback().accept(e);
                });
            }
        }
    }

    /**
     * 提交数据库任务
     * @param name 任务名称（用于日志和慢查询检测）
     * @param operation 数据库操作（接收 Connection，由队列管理）
     * @param callback 成功回调（可选，自动回到主线程）
     * @param errorCallback 错误回调（可选，自动回到主线程）
     */
    public <T> void submit(String name, DatabaseOperation<T> operation, Consumer<T> callback, Consumer<SQLException> errorCallback) {
        if (!running) {
            plugin.getLogger().warning("Database queue is closed, cannot submit task: " + name);
            return;
        }

        DatabaseTask<T> task = new DatabaseTask<>(name, operation, callback, errorCallback);
        try {
            taskQueue.offer(task, 5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            plugin.getLogger().warning("Submit database task interrupted: " + name);
        }
    }

    /**
     * 提交无返回值的数据库任务
     */
    public void submit(String name, DatabaseOperation<Void> operation) {
        submit(name, operation, null, null);
    }

    public void shutdown() {
        running = false;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
        }
    }

    @FunctionalInterface
    public interface DatabaseOperation<T> {
        T execute(Connection connection) throws SQLException;
    }

    private static class DatabaseTask<T> {
        private final String name;
        private final DatabaseOperation<T> operation;
        private final Consumer<T> callback;
        private final Consumer<SQLException> errorCallback;

        public DatabaseTask(String name, DatabaseOperation<T> operation, Consumer<T> callback, Consumer<SQLException> errorCallback) {
            this.name = name;
            this.operation = operation;
            this.callback = callback;
            this.errorCallback = errorCallback;
        }

        public String getName() { return name; }
        public DatabaseOperation<T> getOperation() { return operation; }
        public Consumer<T> getCallback() { return callback; }
        public Consumer<SQLException> getErrorCallback() { return errorCallback; }
    }
}
