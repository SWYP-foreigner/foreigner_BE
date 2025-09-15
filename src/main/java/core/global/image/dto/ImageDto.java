package core.global.image.dto;



public record ImageDto(
        Long id,
        String imageType,
        Long relatedId,
        String url,
        Integer orderIndex
) {}