import { createTemplateAction } from '@backstage/plugin-scaffolder-node';
import { Config } from '@backstage/config';
import AdmZip from 'adm-zip';

export function createGenerateAction(config: Config) {
  return createTemplateAction({
    id: 'agent-starter:generate',
    description: 'Generates an agent project from the Agent Starter service',
    schema: {
      input: {
        agentName: z => z.string(),
        agentDescription: z => z.string(),
        agentVersion: z => z.string().default('0.1.0'),
        language: z => z.enum(['python', 'kotlin', 'n8n']),
        agenticFramework: z => z.string(),
        serviceFramework: z => z.string(),
        dependencyManager: z => z.string(),
        connectedServices: z => z.array(z.string()).default([]),
      },
    },
    async handler(ctx) {
      const input = ctx.input;
      const baseUrl = config.getString('agentStarter.baseUrl');
      const healthUrl = `${baseUrl}/health`;
      const generateUrl = `${baseUrl}/api/v1/generate`;

      ctx.logger.info(`Agent Starter service base URL: ${baseUrl}`);

      ctx.logger.info('Checking Agent Starter service health...');
      let healthCheckFailed = false;
      try {
        const healthResponse = await fetch(healthUrl, {
          method: 'GET',
          headers: { 'Content-Type': 'application/json' },
        });

        if (!healthResponse.ok) {
          healthCheckFailed = true;
        }
      } catch {
        healthCheckFailed = true;
      }

      if (healthCheckFailed) {
        throw new Error(`Agent Starter service is not reachable at ${healthUrl}`);
      }
      ctx.logger.info('Agent Starter service is healthy');

      ctx.logger.info(`Generating agent project: ${input.agentName}`);

      let zipBuffer: ArrayBuffer | undefined;
      let downloadId: string | undefined;
      let generationError: Error | undefined;
      try {
        const response = await fetch(generateUrl, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            agentName: input.agentName,
            agentDescription: input.agentDescription,
            agentVersion: input.agentVersion,
            language: input.language,
            agenticFramework: input.agenticFramework,
            serviceFramework: input.serviceFramework,
            dependencyManager: input.dependencyManager,
            connectedServices: input.connectedServices,
          }),
        });

        if (!response.ok) {
          let errorMessage = `Generation failed with status ${response.status}`;
          try {
            const errorBody = await response.json();
            if (errorBody.error) {
              errorMessage = errorBody.error;
            }
          } catch {
            // response body is not JSON, use status text
          }
          generationError = new Error(errorMessage);
        } else {
          zipBuffer = await response.arrayBuffer();
          downloadId = response.headers.get('X-Download-Id') ?? undefined;
          ctx.logger.info('Received ZIP from Agent Starter service');
          if (downloadId) {
            ctx.logger.info(`Download ID: ${downloadId}`);
          }
        }
      } catch (error) {
        generationError = new Error(
          `Failed to call Agent Starter service at ${generateUrl}: ${error instanceof Error ? error.message : String(error)}`,
        );
      }

      if (generationError) {
        throw generationError;
      }

      if (zipBuffer === undefined || zipBuffer.byteLength === 0) {
        throw new Error('Agent Starter service returned an empty ZIP');
      }

      ctx.logger.info(`Received ZIP buffer, size: ${zipBuffer.byteLength} bytes`);

      const zipFileName = `${input.agentName}.zip`;
      ctx.logger.info(`Loading ZIP into adm-zip...`);

      let zip: AdmZip;
      try {
        zip = new AdmZip(Buffer.from(zipBuffer));
      } catch (error) {
        throw new Error(
          `Failed to parse ZIP from Agent Starter service: ${error instanceof Error ? error.message : String(error)}`,
        );
      }

      const zipEntries = zip.getEntries();
      ctx.logger.info(`ZIP contains ${zipEntries.length} entries`);

      if (zipEntries.length === 0) {
        throw new Error('Received invalid ZIP from Agent Starter service (no entries)');
      }

      ctx.logger.info(`Extracting ZIP contents to workspace: ${ctx.workspacePath}`);

      try {
        zip.extractAllTo(ctx.workspacePath, true);
      } catch (error) {
        throw new Error(
          `Failed to extract ZIP to workspace: ${error instanceof Error ? error.message : String(error)}`,
        );
      }

      // Build the download URL via the Backstage proxy
      if (downloadId) {
        const downloadUrl = `${baseUrl}/api/v1/download/${downloadId}`;
        ctx.logger.info(`Download URL: ${downloadUrl}`);
        ctx.output('downloadUrl', downloadUrl);
      }

      ctx.output('zipFileName', zipFileName);
      ctx.output('zipEntriesCount', zipEntries.length);

      ctx.logger.info(`Agent project generated successfully: ${zipFileName}`);
      ctx.logger.info(`Extracted ${zipEntries.length} files to workspace: ${ctx.workspacePath}`);
    },
  });
}
