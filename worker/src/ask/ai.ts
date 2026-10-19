// The one place the Workers AI binding is touched, behind an interface so tests (and the
// route's tests) use a fake. The binding is remote-only, so nothing in the test suite may
// reach a real one.

export interface AiRunner {
  run(model: string, input: object, timeoutMs: number): Promise<unknown>;
}

interface RunnableAi {
  run(model: string, input: object, options: { signal: AbortSignal }): Promise<unknown>;
}

/** Races `ai.run` against a timer, and aborts the call when the timer wins. */
export function bindingRunner(ai: Ai): AiRunner {
  return {
    async run(model, input, timeoutMs) {
      const controller = new AbortController();
      let stopTimer = () => {};
      const timedOut = new Promise<never>((_resolve, reject) => {
        const timer = setTimeout(() => {
          controller.abort();
          reject(new Error(`timeout after ${timeoutMs} ms`));
        }, timeoutMs);
        stopTimer = () => clearTimeout(timer);
      });
      // Started inside a promise so a missing binding or a synchronous throw is a rejection.
      const call = Promise.resolve().then(() =>
        (ai as unknown as RunnableAi).run(model, input, { signal: controller.signal }),
      );
      try {
        return await Promise.race([call, timedOut]);
      } finally {
        stopTimer();
      }
    },
  };
}
