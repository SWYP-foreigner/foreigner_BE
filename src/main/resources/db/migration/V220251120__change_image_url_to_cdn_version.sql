-- cdn.ko-ri.cloud -> guzgfxgfnhlk28883169.gcdn.ntruss.com 로 URL 일괄 변경

UPDATE image
SET url = REPLACE(
        url,
        'https://cdn.ko-ri.cloud',
        'https://guzgfxgfnhlk28883169.gcdn.ntruss.com'
          )
WHERE url LIKE 'https://cdn.ko-ri.cloud/%';