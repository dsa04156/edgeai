/* global SwaggerUIBundle */
window.addEventListener("load", () => {
  SwaggerUIBundle({
    url: new URLSearchParams(window.location.search).get("contract") === "streams" ? "/stream-openapi.yaml" : "/openapi.yaml",
    dom_id: "#swagger-ui",
    deepLinking: true,
    displayRequestDuration: true,
    persistAuthorization: false,
    showMutatedRequest: false,
    validatorUrl: null,
    presets: [SwaggerUIBundle.presets.apis],
    // Management requests need no account. Writes still use the session CSRF token.
    requestInterceptor: async request => {
      const target = new URL(request.url, window.location.origin);
      if (target.origin !== window.location.origin) {
        throw new Error("API requests must stay on this Control Plane origin.");
      }
      const prepared = { ...request, headers: { ...request.headers }, credentials: "same-origin" };
      if (!target.pathname.startsWith("/internal/") && !["GET", "HEAD", "OPTIONS"].includes((request.method || "GET").toUpperCase())) {
        const response = await fetch("/api/v1/csrf", {
          credentials: "same-origin", cache: "no-store", signal: AbortSignal.timeout(5000),
        });
        if (!response.ok) throw new Error("CSRF token request failed. Reload the page and try again.");
        prepared.headers["X-CSRF-TOKEN"] = (await response.json()).token;
      }
      return prepared;
    },
  });
});
