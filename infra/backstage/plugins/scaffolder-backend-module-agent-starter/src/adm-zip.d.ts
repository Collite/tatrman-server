declare module 'adm-zip' {
  export default class AdmZip {
    constructor(zipPathOrBuffer: Buffer | string);
    writeZip(zipPath: string): void;
    getEntries(): AdmZipEntry[];
    extractAllTo(targetPath: string, overwrite: boolean): void;
  }

  interface AdmZipEntry {
    entryName: string;
    isDirectory: boolean;
    getData(): Buffer;
  }
}
