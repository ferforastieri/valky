package auth

import (
	"context"
	"database/sql"
	"fmt"
	"strings"
	"time"
)

type ManagedUser struct {
	ID      string `json:"id"`
	Name    string `json:"name"`
	Enabled bool   `json:"enabled"`
	Admin   bool   `json:"admin"`
	Devices int    `json:"devices"`
}

func (m *Manager) Users(ctx context.Context) ([]ManagedUser, error) {
	rows, err := m.store.DB.QueryContext(ctx, `SELECT u.id,u.name,u.enabled,COALESCE(MAX(d.is_admin),0),COUNT(d.id) FROM users u LEFT JOIN devices d ON d.user_id=u.id GROUP BY u.id ORDER BY u.name COLLATE NOCASE`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []ManagedUser{}
	for rows.Next() {
		var u ManagedUser
		if err := rows.Scan(&u.ID, &u.Name, &u.Enabled, &u.Admin, &u.Devices); err != nil {
			return nil, err
		}
		out = append(out, u)
	}
	return out, rows.Err()
}
func (m *Manager) ManageUser(ctx context.Context, id string, in ManagedUser, remove bool) error {
	tx, err := m.store.DB.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var exists bool
	if err = tx.QueryRowContext(ctx, `SELECT EXISTS(SELECT 1 FROM users WHERE id=?)`, id).Scan(&exists); err != nil {
		return err
	}
	if !exists {
		return sql.ErrNoRows
	}
	if remove || !in.Enabled || !in.Admin {
		var admins int
		err = tx.QueryRowContext(ctx, `SELECT count(*) FROM devices d JOIN users u ON u.id=d.user_id WHERE d.is_admin=1 AND d.enabled=1 AND u.enabled=1 AND u.id<>?`, id).Scan(&admins)
		if err != nil {
			return err
		}
		if admins == 0 {
			return fmt.Errorf("keep at least one active administrator")
		}
	}
	if remove {
		// Revoke credentials before deleting the profile; sessions cannot become unowned.
		if _, err = tx.ExecContext(ctx, `DELETE FROM devices WHERE user_id=?`, id); err != nil {
			return err
		}
		_, err = tx.ExecContext(ctx, `DELETE FROM users WHERE id=?`, id)
	} else {
		name := strings.TrimSpace(in.Name)
		if name == "" || len(name) > 100 {
			return fmt.Errorf("user name must contain 1 to 100 bytes")
		}
		_, err = tx.ExecContext(ctx, `UPDATE users SET name=?,enabled=?,updated_at=? WHERE id=?`, name, in.Enabled, time.Now().UTC().Format(time.RFC3339Nano), id)
		if err == nil {
			_, err = tx.ExecContext(ctx, `UPDATE devices SET is_admin=? WHERE user_id=?`, in.Admin, id)
		}
	}
	if err != nil {
		return err
	}
	return tx.Commit()
}
