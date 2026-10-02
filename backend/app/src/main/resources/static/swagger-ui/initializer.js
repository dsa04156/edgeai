/* global SwaggerUIBundle */
window.addEventListener("load", () => {
  SwaggerUIBundle({
    url: "/openapi.yaml",
    dom_id: "#swagger-ui",
    deepLinking: true,
    displayRequestDuration: true,
    persistAuthorization: false,
    showMutatedRequest: false,
    validatorUrl: null,
    presets: [SwaggerUIBundle.presets.apis],
    // Existing HTTP Basic authentication protects docs and API alike. Credentials
    // entered in Authorize are kept in memory; cookie-based CSRF remains enabled.
    requestInterceptor: async request => {
      const target = new URL(request.url, window.location.origin);
      if (target.origin !== window.location.origin) {
        throw new Error("API requests must stay on this Control Plane origin.");
      }
      const prepared = { ...request, headers: { ...request.headers }, credentials: "same-origin" };
      if (!["GET", "HEAD", "OPTIONS"].includes((request.method || "GET").toUpperCase())) {
        const headers = new Headers();
        const authorization = prepared.headers.Authorization || prepared.headers.authorization;
        if (authorization) headers.set("Authorization", authorization);
        const response = await fetch("/api/v1/csrf", {
          credentials: "same-origin", cache: "no-store", headers, signal: AbortSignal.timeout(5000),
        });
        if (!response.ok) throw new Error("CSRF token request failed. Check your development account.");
        prepared.headers["X-CSRF-TOKEN"] = (await response.json()).token;
      }
      return prepared;
    },
  });
});
