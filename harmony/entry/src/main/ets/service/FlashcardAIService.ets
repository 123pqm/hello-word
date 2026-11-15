import { http } from '@kit.NetworkKit';
import { util } from '@kit.ArkTS';
import { AppConfig } from '../common/AppConfig';

export interface AIChatMessage { role: string; content: string; }
interface AIEvent { type: string; content?: string; message?: string; }

export class AIStreamParser {
  private buffer: string = '';
  answer: string = '';
  done: boolean = false;
  feed(text: string, onText: (answer: string) => void): void {
    if (this.done) return;
    this.buffer += text;
    if (this.buffer.length > 1048576) throw new Error('AI 响应过大，请重试');
    let boundary = /\r?\n\r?\n/.exec(this.buffer);
    while (boundary && !this.done) {
      const block = this.buffer.slice(0, boundary.index);
      this.buffer = this.buffer.slice(boundary.index + boundary[0].length);
      const data = block.split(/\r?\n/).filter((line: string) => line.startsWith('data:'))
        .map((line: string) => line.slice(5).replace(/^ /, '')).join('\n');
      if (data) {
        const event = JSON.parse(data) as AIEvent;
        if (event.type === 'error') throw new Error(event.message || 'AI 回答失败，请重试');
        if (event.type === 'delta' && typeof event.content === 'string') {
          this.answer += event.content;
          onText(this.answer);
        }
        if (event.type === 'done') this.done = true;
      }
      boundary = /\r?\n\r?\n/.exec(this.buffer);
    }
  }
}

export class FlashcardAIService {
  private cancelCurrent: (() => void) | undefined = undefined;
  cancel(): void { this.cancelCurrent?.(); }

  ask(messages: AIChatMessage[], word: string, sentence: string, token: string,
    onText: (answer: string) => void = () => {}): Promise<string> {
    this.cancel();
    const context: AIChatMessage[] = messages.map((item: AIChatMessage) => {
      return { role: item.role, content: item.content.slice(0, 8000) } as AIChatMessage;
    });
    while (context.reduce((total: number, item: AIChatMessage) => total + item.content.length, 0) > 30000) {
      context.splice(0, 2);
    }
    return new Promise<string>((resolve, reject) => {
      const request = http.createHttp();
      const parser = new AIStreamParser();
      const decoder = new util.TextDecoder('utf-8');
      let settled = false;
      let ended = false;
      let status = 0;
      let timeout = -1;
      const finish = (error?: Error): void => {
        if (settled) return;
        settled = true;
        if (timeout !== -1) clearTimeout(timeout);
        this.cancelCurrent = undefined;
        request.off('dataReceive');
        request.off('dataEnd');
        request.destroy();
        if (error) reject(error);
        else resolve(parser.answer);
      };
      const check = (): void => {
        if (settled || status === 0) return;
        if (status !== 200) { finish(new Error(`AI 服务暂不可用（${status}），请稍后重试`)); return; }
        if (ended) {
          if (!parser.done || !parser.answer.trim()) finish(new Error('连接中断或回答未完成，请重试'));
          else finish();
        }
      };
      this.cancelCurrent = () => finish(new Error('已取消回答'));
      timeout = setTimeout(() => finish(new Error('AI 回答超时，请重试')), 190000);
      request.on('dataReceive', (data: ArrayBuffer) => {
        if (settled) return;
        try {
          parser.feed(decoder.decodeToString(new Uint8Array(data), { stream: true }), onText);
        } catch (error) { finish(error as Error); }
      });
      request.on('dataEnd', () => {
        if (settled) return;
        try {
          parser.feed(decoder.decodeToString(new Uint8Array(0), { stream: false }), onText);
          ended = true;
          check();
        } catch (error) { finish(error as Error); }
      });
      const headers: Record<string, string> = { 'Content-Type': 'application/json', 'Accept': 'text/event-stream' };
      if (token) headers['Authorization'] = `Bearer ${token}`;
      request.requestInStream(`${AppConfig.BASE_URL}/ai/chat/stream`, {
        method: http.RequestMethod.POST, header: headers,
        extraData: JSON.stringify({ messages: context, word: word.slice(0, 100), sentence: sentence.slice(0, 4000) }),
        connectTimeout: 15000, readTimeout: 180000, usingCache: false
      }).then((code: number) => { status = code; check(); })
        .catch((error: Error) => finish(error));
    });
  }
}
