import { saveBlob } from '@/api/client';
import { cmrApi, type Cmr } from '@/api/cmr';

/** The number inside "CMR {{number}} issued" without its own CMR/ prefix, so the text does not say "CMR CMR/2026/0001". */
export const numberInText = (number: string): string => number.replace(/^CMR[\s/-]*/i, '') || number;

/** "1 · Sender": the box number of the international form before the caption. */
export function boxLabel(box: string | number, caption: string): string {
  return `${box} · ${caption}`;
}

/**
 * Opens the PDF in a new tab (the tab is opened before the download so pop-up blockers let it through) or saves it.
 * A draft is drawn with `copies` pages; issued notes give the stored four copies.
 */
export async function showCmrPdf(c: Pick<Cmr, 'id' | 'number'>, copies: number, save: boolean): Promise<void> {
  const tab = save ? null : window.open('', '_blank');
  try {
    const { blob, fileName } = await cmrApi.pdf(c.id, copies);
    if (tab) {
      const url = URL.createObjectURL(blob);
      tab.location.href = url;
      setTimeout(() => URL.revokeObjectURL(url), 60_000);
    } else {
      saveBlob(blob, fileName ?? `${c.number.replace(/\//g, '-')}.pdf`);
    }
  } catch (e) {
    tab?.close();
    throw e;
  }
}
