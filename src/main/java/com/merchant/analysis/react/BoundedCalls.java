package com.merchant.analysis.react;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** 有界工作线程，超时取消；即使第三方忽略中断，也不无限创建线程或排队。 */
@Component
public class BoundedCalls {
    private final ExecutorService models = pool("react-model", 4);
    private final ExecutorService tools = pool("react-tool", 4);
    private static ExecutorService pool(String prefix, int size) {
        AtomicInteger sequence = new AtomicInteger();
        return new ThreadPoolExecutor(0, size, 30, TimeUnit.SECONDS, new SynchronousQueue<>(), task -> {
            Thread thread = new Thread(task, prefix + "-" + sequence.incrementAndGet()); thread.setDaemon(true); return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }
    public <T> T model(Callable<T> task, long timeoutMs) throws Exception { return call(models, task, timeoutMs); }
    public <T> T tool(Callable<T> task, long timeoutMs) throws Exception { return call(tools, task, timeoutMs); }
    private static <T> T call(ExecutorService executor, Callable<T> task, long timeoutMs) throws Exception {
        if (timeoutMs < 1) throw new TimeoutException("运行预算耗尽");
        Future<T> future = executor.submit(task);
        try { return future.get(timeoutMs, TimeUnit.MILLISECONDS); }
        catch (ExecutionException e) {
            if (e.getCause() instanceof Exception cause) throw cause;
            throw new IllegalStateException("后台调用失败", e.getCause());
        } finally { if (!future.isDone()) future.cancel(true); }
    }
    @PreDestroy public void close() { models.shutdownNow(); tools.shutdownNow(); }
}
