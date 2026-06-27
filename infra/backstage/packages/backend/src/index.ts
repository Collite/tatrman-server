/*
 * Hi!
 *
 * Note that this is an EXAMPLE Backstage backend. Please check the README.
 *
 * Happy hacking!
 */

import { createBackend } from '@backstage/backend-defaults';
import {authProvidersExtensionPoint, createOAuthProviderFactory} from "@backstage/plugin-auth-node";
import { oidcAuthenticator } from '@backstage/plugin-auth-backend-module-oidc-provider';
import { createBackendModule } from '@backstage/backend-plugin-api';


const backend = createBackend();

backend.add(import('@backstage/plugin-app-backend'));
backend.add(import('@backstage/plugin-proxy-backend'));

// scaffolder plugin
backend.add(import('@backstage/plugin-scaffolder-backend'));
backend.add(import('@backstage/plugin-scaffolder-backend-module-github'));
backend.add(
  import('@backstage/plugin-scaffolder-backend-module-notifications'),
);
backend.add(
  import('@internal/plugin-scaffolder-backend-module-agent-starter'),
);

// techdocs plugin
backend.add(import('@backstage/plugin-techdocs-backend'));

// auth plugin
backend.add(import('@backstage/plugin-auth-backend'));
// See https://backstage.io/docs/backend-system/building-backends/migrating#the-auth-plugin
backend.add(import('@backstage/plugin-auth-backend-module-guest-provider'));
// See https://backstage.io/docs/auth/guest/provider

// catalog plugin
backend.add(import('@backstage/plugin-catalog-backend'));
backend.add(
  import('@backstage/plugin-catalog-backend-module-scaffolder-entity-model'),
);

// See https://backstage.io/docs/features/software-catalog/configuration#subscribing-to-catalog-errors
backend.add(import('@backstage/plugin-catalog-backend-module-logs'));

// permission plugin
backend.add(import('@backstage/plugin-permission-backend'));
// See https://backstage.io/docs/permissions/getting-started for how to create your own permission policy
backend.add(
  import('@backstage/plugin-permission-backend-module-allow-all-policy'),
);

// search plugin
backend.add(import('@backstage/plugin-search-backend'));

// search engine
// See https://backstage.io/docs/features/search/search-engines
backend.add(import('@backstage/plugin-search-backend-module-pg'));

// search collators
backend.add(import('@backstage/plugin-search-backend-module-catalog'));
backend.add(import('@backstage/plugin-search-backend-module-techdocs'));

// kubernetes plugin
backend.add(import('@backstage/plugin-kubernetes-backend'));

// notifications and signals plugins
backend.add(import('@backstage/plugin-notifications-backend'));
backend.add(import('@backstage/plugin-signals-backend'));

const customOidcAuthModule = createBackendModule({
    // This must explicitly target the 'auth' plugin
    pluginId: 'auth',
    // Give your custom module a unique ID
    moduleId: 'keycloak-oidc-provider',
    register(reg) {
        reg.registerInit({
            deps: { providers: authProvidersExtensionPoint },
            async init({ providers }) {
                providers.registerProvider({
                    providerId: 'oidc',
                    factory: createOAuthProviderFactory({
                        authenticator: oidcAuthenticator,
                        async signInResolver(info, ctx) {
                            // Access the raw profile payload from Keycloak
                            const fullProfile = info.result.fullProfile as Record<string, any>;
                            const userEmail = info.profile.email;
                            let uname = fullProfile.username?.toLowerCase();
                            console.log(uname)
                            console.log(fullProfile)
                            console.log(userEmail)

                            // Extract and lowercase the standard Keycloak username claim
                            let username = fullProfile.preferred_username?.toLowerCase();

                            if (!username)
                                username = uname

                            if (!username) {
                                if (userEmail) {
                                    // 2. Strip the domain to match your catalog User entity names
                                    // E.g., john.doe@kantheon.example -> john.doe
                                    username = userEmail.split('@')[0];
                                }
                            }

                            if (!username) {
                                throw new Error('Login failed: Keycloak profile did not contain an email');
                            }

                            // Issue the Backstage token tying the session to the Catalog User
                            return ctx.issueToken({
                                claims: {
                                    sub: `user:default/${username}`,
                                    ent: [`user:default/${username}`],
                                },
                            });
                        },
                    }),
                });
            },
        });
    },
});

backend.add(customOidcAuthModule);

backend.start();
