package dev.ryanpark.reservation.demo;

import dev.ryanpark.reservation.common.ApiException;
import dev.ryanpark.reservation.demo.ManualDemoService.Command;
import dev.ryanpark.reservation.demo.ManualDemoService.State;
import dev.ryanpark.reservation.demo.ManualDemoService.Workspace;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.WebUtils;

@RestController
@Profile({"demo", "public-demo"})
public class ManualDemoController {
  private static final String WORKSPACE = ManualDemoController.class.getName() + ".workspace";
  private final ManualDemoService manual;
  private final DemoAccess access;

  public ManualDemoController(ManualDemoService manual, DemoAccess access) {
    this.manual = manual;
    this.access = access;
  }

  @PostMapping("/api/demo/manual")
  public State open(HttpServletRequest request) {
    access.requireHost(request);
    var session = request.getSession();
    synchronized (WebUtils.getSessionMutex(session)) {
      var workspace = (Workspace) session.getAttribute(WORKSPACE);
      if (workspace == null) {
        workspace = manual.create();
        session.setAttribute(WORKSPACE, workspace);
      }
      return manual.state(workspace);
    }
  }

  @GetMapping("/api/demo/manual")
  public State state(HttpServletRequest request) {
    access.requireHost(request);
    return manual.state(workspace(request.getSession(false)));
  }

  @PostMapping("/api/demo/manual/actions")
  public ResponseEntity<State> act(
      @Valid @RequestBody Command command, HttpServletRequest request) {
    access.requireHost(request);
    var outcome = manual.act(workspace(request.getSession(false)), command);
    var response = ResponseEntity.status(outcome.statusCode());
    if (outcome.statusCode() == 429) {
      response.header(
          "Retry-After", Long.toString(Math.max(1, (outcome.state().retryAfterMs() + 999) / 1000)));
    }
    return response.body(outcome.state());
  }

  private Workspace workspace(HttpSession session) {
    if (session == null || !(session.getAttribute(WORKSPACE) instanceof Workspace workspace)) {
      throw new ApiException(
          HttpStatus.NOT_FOUND,
          "MANUAL_NOT_STARTED",
          "Open manual mode to join the shared inventory");
    }
    return workspace;
  }
}
