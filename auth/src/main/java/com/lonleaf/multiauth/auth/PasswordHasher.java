package com.lonleaf.multiauth.auth;

import com.lonleaf.multiauth.Messages;
import de.mkammerer.argon2.Argon2;
import de.mkammerer.argon2.Argon2Factory;

import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class PasswordHasher {

    // ThreadLocal：argon2-jvm 的 Argon2 实例非线程安全，2 线程池每线程独占一个实例，
    // 避免并发 hash/verify 时共享实例产生竞态
    private final ThreadLocal<Argon2> argon2 = ThreadLocal.withInitial(
            () -> Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id));
    private final ExecutorService executor;
    private final Logger logger = Logger.getLogger("MultiAuth");

    public PasswordHasher() {
        // 有界队列 + AbortPolicy：攻击者用大量不同账号并发 /login 时，验证任务（每任务约 32MiB 内存 +
        // Argon2id 计算）无法无限堆积，队列满即拒绝新任务并返回失败 future（fail-closed）（#7）
        this.executor = new ThreadPoolExecutor(
                2, 2, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(16),
                r -> {
                    Thread t = new Thread(r, "multiauth-argon2");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * 异步哈希密码。
     *
     * @param password 明文密码
     * @return CompletableFuture，完成后返回 Argon2id 哈希字符串；线程池满载时返回异常完成的 future
     */
    public CompletableFuture<String> hash(String password) {
        try {
            return CompletableFuture.supplyAsync(() -> {
                char[] chars = password.toCharArray();
                try {
                    // m=32768(32MiB), t=2, p=1
                    return argon2.get().hash(2, 32768, 1, chars);
                } finally {
                    Arrays.fill(chars, '\0');
                }
            }, executor);
        } catch (RejectedExecutionException e) {
            // 队列已满：返回失败 future，由调用方（AuthService）按注册/登录失败处理，不吞异常
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * 异步验证密码。
     *
     * @param password     明文密码
     * @param passwordHash 已存储的 Argon2id 哈希
     * @return CompletableFuture，完成后返回 true 表示匹配；线程池满载时返回异常完成的 future
     */
    public CompletableFuture<Boolean> verify(String password, String passwordHash) {
        try {
            return CompletableFuture.supplyAsync(() -> {
                char[] chars = password.toCharArray();
                try {
                    return argon2.get().verify(passwordHash, chars);
                } catch (IllegalArgumentException e) {
                    logger.log(Level.SEVERE, Messages.get(Messages.AUTH_INVALID_PASSWORD_HASH_FORMAT, e.getMessage()), e);
                    return false;
                } catch (Exception e) {
                    logger.log(Level.WARNING, Messages.get(Messages.AUTH_PASSWORD_VERIFY_ERROR, e.getMessage()), e);
                    return false;
                } finally {
                    Arrays.fill(chars, '\0');
                }
            }, executor);
        } catch (RejectedExecutionException e) {
            // 队列已满：返回失败 future，由调用方（AuthService.login）按登录失败处理
            return CompletableFuture.failedFuture(e);
        }
    }

    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
