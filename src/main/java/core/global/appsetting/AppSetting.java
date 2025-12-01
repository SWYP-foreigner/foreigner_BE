package core.global.appsetting;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor
@Table(name = "app_settings")
public class AppSetting {

    @Id
    @Column(name = "setting_key")
    private String key;

    @Column(name = "setting_value", nullable = false)
    private String value;

    private String description;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public void updateValue(String newValue) {
        this.value = newValue;
        this.updatedAt = LocalDateTime.now();
    }
}