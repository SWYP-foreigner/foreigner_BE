package core.global.smoke;

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

        // 지금은 gate만
        SmokeResult result = runner.runGate();
        return result.ok()
                ? ResponseEntity.ok(result)
                : ResponseEntity.status(500).body(result);
    }
}
