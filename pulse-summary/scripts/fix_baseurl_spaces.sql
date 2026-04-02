-- Fix agents with leading/trailing spaces in base_url
-- Run this in MySQL to clean existing data

UPDATE agents
SET base_url = TRIM(REPLACE(base_url, '%20', ''))
WHERE base_url LIKE '% %' OR base_url LIKE '%%20%' OR base_url LIKE '% ';

-- Verify the fix
SELECT id, name, base_url FROM agents WHERE base_url IS NOT NULL;