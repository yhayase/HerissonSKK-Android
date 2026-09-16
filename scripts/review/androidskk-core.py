#!/usr/bin/env python3
"""AndroidSKK の実ソースを最小の Android 代替クラスと実 JDBM で実行する調査用ハーネスです。"""
import argparse
import json
from pathlib import Path
import re
import subprocess
import tempfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('repository', type=Path)
args = parser.parse_args()
repo = args.repository.resolve()
core = repo / 'app/src/main/java/io/github/kachaya/skk/engine'
root = Path(tempfile.mkdtemp(prefix='androidskk-audit-'))
src = root / 'src'

def write(name, content):
    path = src / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content)

stubs = {
'android/content/Context.java': '''package android.content; public class Context {
 public java.io.File dir, raw; public Context(java.io.File d,java.io.File r){dir=d;raw=r;}
 public java.io.File getFilesDir(){return dir;} public Object getTheme(){return null;}
 public android.content.res.Resources getResources(){return new android.content.res.Resources(raw);}
}''',
'android/content/SharedPreferences.java': '''package android.content; public class SharedPreferences {
 public static final java.util.Map<String,Boolean> values=new java.util.HashMap<>();
 public boolean getBoolean(String k,boolean d){return values.getOrDefault(k,d);}
}''',
'android/content/res/Resources.java': '''package android.content.res; public class Resources {
 private final java.io.File raw; public Resources(java.io.File r){raw=r;}
 public int getColor(int id,Object theme){return 0;}
 public AssetFileDescriptor openRawResourceFd(int id){return null;}
 public java.io.InputStream openRawResource(int id){try{return new java.io.FileInputStream(raw);}catch(Exception e){throw new RuntimeException(e);}}
}''',
'android/content/res/AssetFileDescriptor.java': '''package android.content.res; public class AssetFileDescriptor implements AutoCloseable {
 public long getLength(){return -1;} public void close(){}
}''',
'android/util/Log.java': '''package android.util; public class Log {
 public static final java.util.List<String> lines=new java.util.ArrayList<>();
 public static int i(String t,String m){lines.add(t+":"+m);return 0;}
 public static int d(String t,String m){return 0;} public static int e(String t,String m){return 0;}
 public static int e(String t,String m,Throwable e){return 0;}
}''',
'android/text/Spanned.java': 'package android.text; public interface Spanned { int SPAN_COMPOSING=256; }',
'android/text/SpannableStringBuilder.java': '''package android.text; public class SpannableStringBuilder implements CharSequence {
 private final StringBuilder b=new StringBuilder(); public SpannableStringBuilder append(CharSequence s){b.append(s);return this;}
 public SpannableStringBuilder append(char c){b.append(c);return this;} public int length(){return b.length();}
 public char charAt(int i){return b.charAt(i);} public CharSequence subSequence(int s,int e){return b.subSequence(s,e);}
 public void setSpan(Object o,int s,int e,int f){} public String toString(){return b.toString();}
}''',
'android/text/style/BackgroundColorSpan.java': 'package android.text.style; public class BackgroundColorSpan {public BackgroundColorSpan(int c){}}',
'android/text/style/UnderlineSpan.java': 'package android.text.style; public class UnderlineSpan {}',
'android/view/inputmethod/InputConnection.java': '''package android.view.inputmethod; public class InputConnection {
 public String committed="", composing=""; public boolean reject=false;
 public boolean commitText(CharSequence t,int p){if(reject)return false;committed+=t;composing="";return true;}
}''',
'androidx/preference/PreferenceManager.java': '''package androidx.preference; public class PreferenceManager {
 public static android.content.SharedPreferences getDefaultSharedPreferences(android.content.Context c){return new android.content.SharedPreferences();}
}''',
'org/json/JSONArray.java': 'package org.json; public class JSONArray { public int length(){return 0;} public JSONObject optJSONObject(int i){return null;} }',
'org/json/JSONObject.java': '''package org.json; public class JSONObject {
 public String optString(String k){return "";} public boolean has(String k){return false;}
 public java.util.Iterator<String> keys(){return java.util.Collections.emptyIterator();}
}''',
'io/github/kachaya/skk/AssetLoader.java': '''package io.github.kachaya.skk; public class AssetLoader {
 public static org.json.JSONArray loadJsonArray(android.content.Context c,String s){throw new AssertionError("未使用の JSON 読み込み");}
 public static org.json.JSONObject loadJsonObject(android.content.Context c,String s){throw new AssertionError("未使用の JSON 読み込み");}
}''',
'io/github/kachaya/skk/BuildConfig.java': 'package io.github.kachaya.skk; public class BuildConfig {public static final boolean DEBUG=false;}',
'io/github/kachaya/skk/InputService.java': '''package io.github.kachaya.skk; public class InputService extends android.content.Context {
 public final android.view.inputmethod.InputConnection connection=new android.view.inputmethod.InputConnection();
 public InputService(java.io.File d,java.io.File r){super(d,r);}
 public android.view.inputmethod.InputConnection getCurrentInputConnection(){return connection;}
 public void hideCandidatesView(){} public void requestChooseCandidate(int i){} public void requestUIUpdate(){}
 public void sendDownUpKeyEvents(int k){} public void setCandidateObjects(java.util.List<?> c){}
 public void setCandidates(java.util.List<String> c){}
 public void setComposingText(CharSequence s,int p){connection.composing=s.toString();}
 public boolean prepareReConversion(String s){return false;}
}''',
}
for name, content in stubs.items(): write(name, content)
all_text='\n'.join(p.read_text() for p in core.glob('*.java'))
keys=sorted(set(re.findall(r'KeyEvent\.(KEYCODE_\w+)',all_text)))
write('android/view/KeyEvent.java','package android.view; public class KeyEvent {'+''.join(f'public static final int {k}={i};' for i,k in enumerate(keys))+'}')
refs=re.findall(r'R\.(\w+)\.(\w+)',all_text)
classes={}
for cls,name in refs: classes.setdefault(cls,set()).add(name)
write('io/github/kachaya/skk/R.java','package io.github.kachaya.skk; public class R {'+''.join('public static class '+cls+' {'+''.join(f'public static final int {name}={i};' for i,name in enumerate(sorted(names)))+'}' for cls,names in classes.items())+'}')
q=lambda s:json.dumps(s,ensure_ascii=True)
rows=json.loads((repo/'app/src/main/assets/romaji_table.json').read_text())
init='\n'.join(f'm.put({q(r["key"])},{q(r["value"])},{q(r.get("next"))});' for r in rows)
init+='\njava.lang.reflect.Field f=RomajiConverter.class.getDeclaredField("romajiMap");f.setAccessible(true);f.set(null,m);'
kana=json.loads((repo/'app/src/main/assets/kana_map.json').read_text())
init+='\njava.util.Map<Character,String> h=new java.util.HashMap<>();'
init+='\n'.join(f'h.put({q(k)}.charAt(0),{q(v)});' for k,v in kana.items())
init+='\nf=RomajiConverter.class.getDeclaredField("halfWidthKatakanaMap");f.setAccessible(true);f.set(null,h);'
write('Audit.java','''package io.github.kachaya.skk.engine;
import io.github.kachaya.skk.InputService;
import java.nio.file.*;
import java.util.List;
import jdbm.*; import jdbm.btree.*; import jdbm.helper.*;
public class Audit {
 static int checks=0;
 static void check(String label,boolean value,Object observed){checks++; if(!value)throw new AssertionError(label+": "+observed);System.out.println(label+": "+observed);}
 static void type(SKKEngine e,String s){s.codePoints().forEach(e::processKey);}
 public static void main(String[] args)throws Exception {
 RomajiMap m=new RomajiMap();
'''+init+'''
 Path dir=Files.createTempDirectory("skk-dict-test-");
 RecordManager rm=RecordManagerFactory.createRecordManager(dir.resolve("fixture").toString());
 BTree tree=BTree.createInstance(rm,new StringComparator()); rm.setNamedObject("skk_dict",tree.getRecid());
 tree.insert("にほん","/日本/二本/",true); tree.insert("かk","/書/",true); rm.commit();rm.close();
 Path files=Files.createDirectory(dir.resolve("files"));
 InputService service=new InputService(files.toFile(),dir.resolve("fixture.db").toFile());
 io.github.kachaya.skk.engine.Dictionary dict=new io.github.kachaya.skk.engine.Dictionary(service);
 SKKEngine e=new SKKEngine(service,dict);
 type(e,"kana");check("基本かな",service.connection.committed.equals("かな"),service.connection.committed);
 service.connection.committed="";type(e,"kitte");check("促音",service.connection.committed.equals("きって"),service.connection.committed);
 service.connection.committed="";type(e,"Nihon ");check("基本変換",e.getCurrentCandidate().equals("日本"),e.getCurrentCandidate());
 e.handleEnter();check("Enter 確定",service.connection.committed.equals("日本"),service.connection.committed);
 check("次の Enter 引渡し",!e.handleEnter(),"未確定なしでは false");
 service.connection.committed="";type(e,"KaKu");check("送りあり",e.getCurrentCandidate().equals("書く"),e.getCurrentCandidate());e.handleEnter();
 dict.importUserDictionary(List.of("literal /(concat \\\"\\\\057\\\")/"));
 check("不具合: concat の取込欠落",dict.findCandidates("literal",null).isEmpty(),dict.exportUserDictionary());
 dict.importUserDictionary(List.of("anno /日本;国名/"));
 Candidate c=dict.findCandidates("anno",null).get(0);
 check("注釈の初回読込",c.candidate.equals("日本")&&"国名".equals(c.annotation),c.candidate+";"+c.annotation);
 dict.addEntry("anno",c.rawCandidate,null);c=dict.findCandidates("anno",null).get(0);
 check("不具合: 学習で注釈が本文化",c.candidate.equals("日本;国名")&&c.annotation==null,c.candidate+", annotation="+c.annotation);
 dict.importUserDictionary(List.of("1234567 /住所/"));
 check("不具合: 数字を含む完全一致キーが検索されない",dict.findCandidates("1234567",null).isEmpty(),"0 候補");
 dict.importUserDictionary(List.of("だい# /第#0/"));
 service.connection.committed="";type(e,"Dai12 ");check("数値テンプレート",e.getCurrentCandidate().equals("第12"),e.getCurrentCandidate());e.handleEnter();
 check("不具合: 数値学習の格納キー不一致",dict.exportUserDictionary().stream().anyMatch(s->s.startsWith("だい12 ")),dict.exportUserDictionary());
 check("不具合: リリース設定でも辞書の入力内容をログへ渡す",android.util.Log.lines.stream().anyMatch(s->s.contains("val=第#0")),android.util.Log.lines);
 service.connection.committed="";type(e,"Nihon ");service.connection.reject=true;e.handleEnter();
 check("不具合: commitText 拒否でも変換終了",service.connection.committed.isEmpty()&&!e.isComposing(),"出力空、未確定なし");service.connection.reject=false;
 java.lang.reflect.Field last=SKKEngine.class.getDeclaredField("mLastConversion");last.setAccessible(true);e.resetOnStartInput();
 check("リスク: 入力先変更後も再変換履歴を保持",last.get(e)!=null,"履歴あり");
 Candidate num=new Candidate("第#4","第#4",null,List.of("12"),false);
 check("仕様差: #4 は辞書再検索でなく旧字体",num.candidate.equals("第拾弐"),num.candidate);
 e.resetOnStartInput();service.connection.committed="";type(e,"Michi miti");
 check("登録中の内部文字",e.peekRegistrationInfo().entry.toString().equals("みち"),e.peekRegistrationInfo().entry);
 e.handleEnter();
 check("不具合: 登録完了時に入力先へ出力されない",service.connection.committed.isEmpty()&&e.isRegistrationStackEmpty(),"登録終了、確定出力は空");
 e.resetOnStartInput();type(e,"Mura aMori mori");e.handleEnter();
 check("不具合: 再帰登録の子が親へ反映されない",e.peekRegistrationInfo().entry.toString().equals("あ"),e.peekRegistrationInfo().entry);
 e.resetOnStartInput();type(e,"n");
 check("不具合: n の Enter が未確定のまま引渡し",!e.handleEnter()&&e.isComposing(),service.connection.composing);
 e.resetOnStartInput();type(e,"Sora ");e.commitTextSKK("😀",1);e.handleBackspace();
 String broken=e.peekRegistrationInfo().entry.toString();
 check("不具合: 登録内削除でサロゲート分断",broken.length()==1&&Character.isHighSurrogate(broken.charAt(0)),"残った UTF-16="+Integer.toHexString(broken.charAt(0)));
 e.resetOnStartInput();dict.importUserDictionary(List.of("あk /飽/"));
 check("不具合: 送りブロックなしのユーザー候補を除外",dict.findCandidates("あk","く").isEmpty(),"0 候補");
 System.out.println("確認数="+checks);
 }
}
''')
classes_dir=root/'classes';classes_dir.mkdir()
jar=repo/'app/libs/jdbm-1.0.jar'
files=list(src.rglob('*.java'))+list(core.glob('*.java'))
subprocess.run(['javac','-encoding','UTF-8','-cp',str(jar),'-d',str(classes_dir),*map(str,files)],check=True)
result=subprocess.run(['java','-cp',f'{classes_dir}:{jar}','io.github.kachaya.skk.engine.Audit'],text=True,capture_output=True,timeout=30)
print(result.stdout,end='');print(result.stderr,end='')
print('ハーネス作業先:',root)
raise SystemExit(result.returncode)
