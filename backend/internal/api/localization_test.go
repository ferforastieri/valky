package api

import "testing"

func TestLocalizedMessageUsesRequestLanguage(t *testing.T) {
	if got := localizedMessage("Place created successfully", true, "pt-BR"); got != "Área criada com sucesso" {
		t.Fatalf("Portuguese message = %q", got)
	}
	if got := localizedMessage("Place created successfully", true, "en"); got != "Place created successfully" {
		t.Fatalf("English message = %q", got)
	}
	if got := localizedMessage("unmapped backend failure", false, "pt-BR"); got != "unmapped backend failure" {
		t.Fatalf("Portuguese fallback = %q", got)
	}
}
