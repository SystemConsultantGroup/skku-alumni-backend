-- Optional MySQL/PXC preparation, not an automatic Flyway migration.
-- Verify ngram support, token size, capacity, and the execution plan on a copy first.
-- Enable search.mysql-ngram-enabled only after this index exists on the target.
CREATE FULLTEXT INDEX ft_posts_title_body_ngram ON posts (title, body) WITH PARSER ngram;
