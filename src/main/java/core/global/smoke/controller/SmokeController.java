package core.global.smoke.controller;

import core.global.smoke.runner.SmokeGateRunner;
import core.global.smoke.utils.SmokeProperties;
import core.global.smoke.dto.SmokeResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal")
public class SmokeController {

    private final SmokeProperties props;
    private final SmokeGateRunner runner;

    public SmokeController(SmokeProperties props, SmokeGateRunner runner) {
        this.props = props;
        this.runner = runner;
    }

    @GetMapping("/smoke")
    public ResponseEntity<SmokeResult> smoke(@RequestParam(defaultValue = "gate") String mode) {
        if (!props.isEnabled()) {
            return ResponseEntity.status(404).build();
        }

        SmokeResult result = runner.run(mode);
        return result.success()
                ? ResponseEntity.ok(result)
                : ResponseEntity.status(500).body(result);
    }
}
