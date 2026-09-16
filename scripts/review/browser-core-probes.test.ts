import { afterEach, expect, it } from 'vitest';
import { MockEditor } from './mocks/MockEditor';
import { EditorFactory } from '../../src/core/skk/editor/EditorFactory';
import { RegistrationMiniBufferEditor } from '../../src/core/skk/input-mode/henkan/RegistrationMode';

afterEach(() => EditorFactory.reset());
it('調査: 未消化 n の Enter は改行も挿入します', async () => {
 const editor = new MockEditor();
 await editor.getCurrentInputMode().lowerAlphabetInput('n');
 await editor.getCurrentInputMode().enterInput();
 expect(editor.getCurrentText()).toBe('ん\n');
});
it('調査: 未消化 n の C-j は残余を破棄します', async () => {
 const editor = new MockEditor();
 await editor.getCurrentInputMode().lowerAlphabetInput('n');
 await editor.getCurrentInputMode().ctrlJInput();
 expect(editor.getCurrentText()).toBe('');
});
it('調査: 登録の内部文字列で絵文字を削除すると上位サロゲートが残ります', async () => {
 const editor = new MockEditor();
 const registration = { notifyChanged: async () => {} };
 const buffer = new RegistrationMiniBufferEditor(registration as any, editor);
 await buffer.insertOrReplaceSelection('😀');
 await buffer.deleteLeft();
 expect(buffer.getCommittedText().length).toBe(1);
 expect(buffer.getCommittedText().charCodeAt(0)).toBe(0xd83d);
});
it('調査: 登録内部の replaceRange は範囲を置換せず末尾に追加します', async () => {
 const editor = new MockEditor();
 const registration = { notifyChanged: async () => {} };
 const buffer = new RegistrationMiniBufferEditor(registration as any, editor);
 await buffer.insertOrReplaceSelection('東京');
 await buffer.replaceRange({ start: { line: 0, character: 0 }, end: { line: 0, character: 1 } }, '京');
 expect(buffer.getCommittedText()).toBe('東京京');
});

it('調査: 送りブロック一致候補を標準候補より前へ移動しません', async () => {
 const { parseJisyoLine } = await import('../../src/core/skk/jisyo/JisyoParser');
 const { Entry } = await import('../../src/core/skk/jisyo/entry');
 const parsed = parseJisyoLine('おおk /大/多/[く/多/]/[き/大/]/')!;
 const entry = new Entry(parsed.key, parsed.candidates, '').forOkuri('く')!;
 expect(entry.getCandidateList().map(c => c.word)).toEqual(['大','多']);
});
