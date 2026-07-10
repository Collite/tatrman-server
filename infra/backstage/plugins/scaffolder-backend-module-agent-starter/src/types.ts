export interface AgentStarterInput {
  agentName: string;
  agentDescription: string;
  agentVersion: string;
  language: 'python' | 'kotlin' | 'n8n';
  agenticFramework: string;
  serviceFramework: string;
  dependencyManager: string;
  connectedServices: string[];
}

export interface AgentStarterOutput {
  result: {
    zipFilePath: string;
    zipFileName: string;
  };
}
