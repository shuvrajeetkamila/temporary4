package android.opengl;
public class GLES30 {
 public static final int GL_VERTEX_SHADER=0,GL_FRAGMENT_SHADER=1,GL_COMPILE_STATUS=2,GL_LINK_STATUS=3,
  GL_DEPTH_TEST=4,GL_LEQUAL=5,GL_CULL_FACE=6,GL_BACK=7,GL_CCW=8,GL_COLOR_BUFFER_BIT=9,GL_DEPTH_BUFFER_BIT=10,
  GL_ARRAY_BUFFER=11,GL_ELEMENT_ARRAY_BUFFER=12,GL_STATIC_DRAW=13,GL_DYNAMIC_DRAW=14,GL_FLOAT=15,
  GL_TRIANGLES=16,GL_UNSIGNED_INT=17,GL_NO_ERROR=0,GL_VENDOR=18,GL_RENDERER=19,GL_VERSION=20;
 public static int glCreateShader(int t){return 0;} public static void glShaderSource(int s,String src){}
 public static void glCompileShader(int s){} public static void glGetShaderiv(int s,int p,int[] v,int o){}
 public static String glGetShaderInfoLog(int s){return "";} public static void glDeleteShader(int s){}
 public static int glCreateProgram(){return 0;} public static void glAttachShader(int p,int s){}
 public static void glLinkProgram(int p){} public static void glGetProgramiv(int p,int n,int[] v,int o){}
 public static String glGetProgramInfoLog(int p){return "";} public static void glDeleteProgram(int p){}
 public static int glGetUniformLocation(int p,String n){return 0;} public static void glUseProgram(int p){}
 public static void glUniform1f(int l,float v){} public static void glUniform3f(int l,float a,float b,float c){}
 public static void glUniform3fv(int l,int n,float[] v,int o){}
 public static void glUniformMatrix4fv(int l,int n,boolean t,float[] v,int o){}
 public static void glEnable(int c){} public static void glDepthFunc(int f){} public static void glCullFace(int f){}
 public static void glFrontFace(int f){} public static void glClearColor(float r,float g,float b,float a){}
 public static void glClear(int m){} public static void glDepthMask(boolean b){} public static void glViewport(int x,int y,int w,int h){}
 public static void glGenVertexArrays(int n,int[] a,int o){} public static void glBindVertexArray(int v){}
 public static void glDeleteVertexArrays(int n,int[] a,int o){}
 public static void glGenBuffers(int n,int[] a,int o){} public static void glBindBuffer(int t,int b){}
 public static void glDeleteBuffers(int n,int[] a,int o){}
 public static void glBufferData(int t,int s,java.nio.Buffer d,int u){}
 public static void glBufferSubData(int t,int off,int s,java.nio.Buffer d){}
 public static void glVertexAttribPointer(int i,int s,int t,boolean n,int st,int off){}
 public static void glEnableVertexAttribArray(int i){} public static void glVertexAttribDivisor(int i,int d){}
 public static void glDrawArrays(int m,int f,int c){} public static void glDrawElements(int m,int c,int t,int o){}
 public static void glDrawElementsInstanced(int m,int c,int t,int o,int n){}
 public static int glGetError(){return 0;} public static String glGetString(int n){return "";} }
