-- dARK: foreign key and indexes of table dark (the table itself is created from the Dark entity).

-- dark.dataset_id is ON DELETE SET NULL: destroying a dataset (including deleting a
-- never-published one) keeps the row so its ARK can be tombstoned. The entity declares
-- ConstraintMode.NO_CONSTRAINT, so this is the only foreign key on the column.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conrelid = 'dark'::regclass AND conname = 'fk_dark_dataset_id') THEN
        ALTER TABLE dark ADD CONSTRAINT fk_dark_dataset_id
            FOREIGN KEY (dataset_id) REFERENCES dvobject(id) ON DELETE SET NULL;
    END IF;
END
$$;

CREATE INDEX IF NOT EXISTS idx_dark_status ON dark(status);
CREATE INDEX IF NOT EXISTS idx_dark_updated_at ON dark(updated_at);
CREATE INDEX IF NOT EXISTS idx_dark_client_item_id ON dark(client_item_id);
CREATE INDEX IF NOT EXISTS idx_dark_reservation_pending ON dark(updated_at) WHERE ark IS NULL;
CREATE INDEX IF NOT EXISTS idx_dark_tombstone_pending ON dark(updated_at) WHERE tombstone_requested AND status <> 'T';
