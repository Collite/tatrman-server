import {
  ScmIntegrationsApi,
  scmIntegrationsApiRef,
  ScmAuth,
} from '@backstage/integration-react';
import {
    AnyApiFactory,
    createApiFactory,
    createApiRef,
    discoveryApiRef,
    oauthRequestApiRef,
    configApiRef,
    OpenIdConnectApi,
    ProfileInfoApi,
    BackstageIdentityApi,
    SessionApi,
} from '@backstage/core-plugin-api';
import { OAuth2 } from '@backstage/core-app-api';

// 1. Manually create the missing reference
export const oidcAuthApiRef = createApiRef<
    OpenIdConnectApi & ProfileInfoApi & BackstageIdentityApi & SessionApi
>({
    id: 'auth.oidc',
});

// 2. Export the APIs array so App.tsx can use it
export const apis: AnyApiFactory[] = [
    createApiFactory({
        api: oidcAuthApiRef,
        deps: {
            discoveryApi: discoveryApiRef,
            oauthRequestApi: oauthRequestApiRef,
            configApi: configApiRef,
        },
        factory: ({ discoveryApi, oauthRequestApi, configApi }) =>
            OAuth2.create({
                discoveryApi,
                oauthRequestApi,
                provider: {
                    id: 'oidc', // This MUST match the provider ID you set in app-config.yaml
                    title: 'Keycloak',
                    icon: () => null,
                },
                environment: configApi.getOptionalString('auth.environment'),
                defaultScopes: ['openid', 'profile', 'email'],
            }),
    }),
    createApiFactory({
        api: scmIntegrationsApiRef,
        deps: { configApi: configApiRef },
        factory: ({ configApi }) => ScmIntegrationsApi.fromConfig(configApi),
    }),
    ScmAuth.createDefaultApiFactory(),
];

