DELIMITER //
CREATE TRIGGER trg_relation_review_history_immutable_update
BEFORE UPDATE ON process_relation_review_history
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Process relation review history is append-only';
END//

CREATE TRIGGER trg_relation_review_history_immutable_delete
BEFORE DELETE ON process_relation_review_history
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Process relation review history is append-only';
END//
DELIMITER ;
