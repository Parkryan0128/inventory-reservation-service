package dev.inventory.demo;

import dev.inventory.common.ApiException;
import dev.inventory.demo.DemoService.Attempt;
import dev.inventory.order.OrderDtos.OrderView;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

final class DemoRequests {
  private DemoRequests() {}

  static String requestId(int index) {
    return String.format(Locale.ROOT, "req-%03d", index + 1);
  }

  static long elapsed(long started) {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
  }

  static Attempt attempt(Work work, DemoActivityLog log) throws Exception {
    long started = System.nanoTime();
    Attempt result;
    try {
      var order = work.call().call();
      result = new Attempt(order.status().name(), order.id(), elapsed(started));
    } catch (ApiException ex) {
      if (ex.status().value() != 409) throw ex;
      result = new Attempt(ex.code(), null, elapsed(started));
    }
    log.record(
        work.requestId(),
        work.actor(),
        work.operation(),
        result.code(),
        result.orderId(),
        work.quantity(),
        work.key(),
        result.durationMs());
    return result;
  }

  static List<Attempt> parallel(int workers, List<Work> calls, DemoActivityLog log)
      throws Exception {
    var ready = new CountDownLatch(Math.min(workers, calls.size()));
    var start = new CountDownLatch(1);
    var futures = new ArrayList<Future<Attempt>>();
    try (var pool = Executors.newFixedThreadPool(workers)) {
      try {
        for (var call : calls) {
          futures.add(
              pool.submit(
                  () -> {
                    ready.countDown();
                    start.await();
                    return attempt(call, log);
                  }));
        }
        if (!ready.await(10, TimeUnit.SECONDS))
          throw new IllegalStateException("Demo workers did not start");
        start.countDown();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        var results = new ArrayList<Attempt>();
        for (var future : futures) {
          results.add(future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
        }
        return results;
      } finally {
        start.countDown();
        for (var future : futures) if (!future.isDone()) future.cancel(true);
      }
    }
  }

  record Work(
      String requestId,
      String actor,
      String operation,
      int quantity,
      String key,
      Callable<OrderView> call) {}
}
