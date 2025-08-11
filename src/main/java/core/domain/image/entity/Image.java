package core.domain.image.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "image")
@Getter
@NoArgsConstructor
public class Image {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "image_id")
    private Long id;

    @Column(name = "image_type", nullable = false)
    private String imageType;

    @Column(name = "related_id", nullable = false)
    private Long relatedId;

    @Column(name = "url", length = 150, nullable = false)
    private String url;
}
