ALTER TABLE public.image ADD CONSTRAINT uq_image_type_related_id_order UNIQUE (image_type, related_id, order_index);

ALTER TABLE public.likes ADD CONSTRAINT uq_likes_user_type_related UNIQUE (user_id, type, related_id);