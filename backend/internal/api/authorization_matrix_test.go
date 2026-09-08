package api

import (
	"context"
	"github.com/ferforastieri/valkyris/backend/internal/auth"
	"github.com/ferforastieri/valkyris/backend/internal/store"
	"go/ast"
	"go/parser"
	"go/token"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"testing"
	"time"
)

// Read every protected route from registration so new endpoints automatically
// participate in the unauthenticated access regression.
func TestEveryProtectedRouteRejectsAnonymous(t *testing.T) {
	tree, err := parser.ParseFile(token.NewFileSet(), "server.go", nil, 0)
	if err != nil {
		t.Fatal(err)
	}
	var routes []string
	ast.Inspect(tree, func(n ast.Node) bool {
		call, ok := n.(*ast.CallExpr)
		if !ok || len(call.Args) == 0 {
			return true
		}
		sel, ok := call.Fun.(*ast.SelectorExpr)
		if !ok {
			return true
		}
		name, ok := sel.X.(*ast.Ident)
		if !ok || name.Name != "protected" {
			return true
		}
		literal, ok := call.Args[0].(*ast.BasicLit)
		if !ok {
			return true
		}
		route, _ := strconv.Unquote(literal.Value)
		routes = append(routes, route)
		return true
	})
	if len(routes) < 50 {
		t.Fatalf("unexpected route coverage: %d", len(routes))
	}
	db, err := store.Open(t.TempDir() + "/routes.db")
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	m := auth.NewManager(db, time.Minute)
	s := NewServer(m, nil, nil, nil, nil, nil, nil, NewHub(), slog.New(slog.NewTextHandler(io.Discard, nil)))
	for _, route := range routes {
		parts := strings.SplitN(route, " ", 2)
		method, path := "GET", parts[0]
		if len(parts) == 2 {
			method, path = parts[0], parts[1]
		}
		path = strings.ReplaceAll(strings.ReplaceAll(path, "{id}", "unknown"), "{session}", "unknown")
		t.Run(route, func(t *testing.T) {
			req := httptest.NewRequest(method, "/api/v1"+path, strings.NewReader(`{"isAdmin":true}`))
			req.Header.Set("X-Is-Admin", "true")
			res := httptest.NewRecorder()
			s.Handler().ServeHTTP(res, req)
			if res.Code != 401 {
				t.Fatalf("anonymous route returned %d: %s", res.Code, res.Body.String())
			}
		})
	}
	session, err := m.CreatePairing(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	member, err := m.Pair(context.Background(), auth.PairRequest{Code: session.Code, DeviceName: "Member"})
	if err != nil {
		t.Fatal(err)
	}
	for _, route := range []string{"GET /admin/users", "PUT /admin/users/other", "DELETE /admin/users/other", "POST /pairing-sessions", "POST /detections", "POST /cameras", "PUT /cameras/other", "DELETE /cameras/other", "PUT /settings/push", "PUT /settings/retention", "POST /system/update", "PUT /rules/other/recipients"} {
		parts := strings.SplitN(route, " ", 2)
		req := httptest.NewRequest(parts[0], "/api/v1"+parts[1], strings.NewReader(`{"isAdmin":true}`))
		req.Header.Set("Authorization", "Bearer "+member.Token)
		req.Header.Set("X-Is-Admin", "true")
		res := httptest.NewRecorder()
		s.Handler().ServeHTTP(res, req)
		if res.Code != http.StatusForbidden {
			t.Errorf("member %s got %d", route, res.Code)
		}
	}
}
