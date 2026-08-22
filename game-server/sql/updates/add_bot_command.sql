-- AION Bot system: grant GM rights for the //bot command on an EXISTING game-server DB.
-- Safe to run repeatedly: if the row already exists, only the help text is refreshed.
-- Target database: al_server_gs (game-server; the one containing the `command` table).
-- Login-server DB (al_server_ls) is NOT touched.

-- Run this whole script against ANY database; the INSERT is fully qualified with the
-- game-server schema name so it never lands in the wrong DB (e.g. aion2_login).
INSERT INTO `al_server_gs`.`command` (`name`, `security`, `help`)
VALUES ('bot', 1, 'Syntax : //bot <spawn [count] | clear | list>')
ON DUPLICATE KEY UPDATE `help` = VALUES(`help`);

-- Verify:
-- SELECT * FROM `al_server_gs`.`command` WHERE `name` = 'bot';
