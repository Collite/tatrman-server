import { createBackendModule, coreServices } from '@backstage/backend-plugin-api';
import { scaffolderActionsExtensionPoint } from '@backstage/plugin-scaffolder-node';
import { createGenerateAction } from './actions/generate';

const module = createBackendModule({
  pluginId: 'scaffolder',
  moduleId: 'agent-starter',
  register(reg) {
    reg.registerInit({
      deps: {
        config: coreServices.rootConfig,
        scaffolder: scaffolderActionsExtensionPoint,
      },
      async init({ config, scaffolder }) {
        scaffolder.addActions(createGenerateAction(config));
      },
    });
  },
});

export default module;
