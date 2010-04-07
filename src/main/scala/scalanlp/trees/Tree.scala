package scalanlp.trees;
/*
 Copyright 2010 David Hall

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
*/


import java.io.DataInput
import java.io.DataOutput
import scala.collection.mutable.ArrayBuffer;
import scalanlp.io.Serialization


case class Tree[+L](label: L, children: Seq[Tree[L]])(val span: Span) {
  def isLeaf = children.size == 0;
  /**
  * A tree is valid if this' span contains all children's spans 
  * and each child abuts the next one.
  */
  def isValid = isLeaf || {
    children.map(_.span).forall(this.span contains _) &&
    children.elements.drop(1).zip(children.elements).forall { case (next,prev) =>
      prev.span.end == next.span.start
    } &&
    children(0).span.start == this.span.start && 
    children.last.span.end == this.span.end
  }

  def leaves:Iterable[Tree[L]] = if(isLeaf) {
    List(this).projection
  } else  {
    children.map(_.leaves).foldLeft[Stream[Tree[L]]](Stream.empty){_ append _}
  }

  def map[M](f: L=>M):Tree[M] = Tree( f(label), children map { _ map f})(span);
  def extend[B](f: Tree[L]=>B):Tree[B] = Tree(f(this), children map { _ extend f})(span);

  def allChildren = preorder;

  def preorder: Iterator[Tree[L]] = {
    children.map(_.preorder).foldLeft( Iterator(this)) { _ ++ _ }
  }

  def postorder: Iterator[Tree[L]] = {
    children.map(_.postorder).foldRight(Iterator(this)){_ ++ _}
  }


  import Tree._;
  override def toString = recursiveToString(this,0,new StringBuilder).toString;
  def render[W](words: Seq[W]) = recursiveRender(this,0,words, new StringBuilder).toString;
}

object Tree {
  def fromString(input: String):(Tree[String],Seq[String]) = new PennTreeReader().readTree(input).left.get;

  private def recursiveToString[L](tree: Tree[L], depth: Int, sb: StringBuilder):StringBuilder = {
    import tree._;
    sb append "( " append tree.label append " [" append span.start append "," append span.end append "] ";
    for( c <- tree.children ) {
      recursiveToString(c,depth+1,sb) append " ";
    }
    sb append ")";
    sb
  }


  private def recursiveRender[L,W](tree: Tree[L], depth: Int, words: Seq[W], sb: StringBuilder): StringBuilder =  {
    import tree._;
      sb append "\n" append "  "*depth append "( " append tree.label
    if(isLeaf) {
      sb append span.map(words).mkString(" "," ","");
    } else {
      //sb append "\n"
      for( c <- children ) {
        recursiveRender(c,depth+1,words,sb);
      }
    }
    sb append ")";
    sb
  }

  implicit def treeSerializationHandler[L:Serialization.Handler]: Serialization.Handler[Tree[L]] = new Serialization.Handler[Tree[L]] {
    def write(t: Tree[L], data: DataOutput) = {
      implicitly[Serialization.Handler[L]].write(t.label,data);
      Serialization.Handlers.seqHandler(this).write(t.children,data);
      data.writeInt(t.span.start);
      data.writeInt(t.span.end);
    }
    def read(data: DataInput) = {
      val label = implicitly[Serialization.Handler[L]].read(data);
      val children = Serialization.Handlers.seqHandler(this).read(data);
      val begin = data.readInt();
      val end = data.readInt();
      new Tree(label,children)(Span(begin,end));
    }
  }

}

sealed trait BinarizedTree[+L] extends Tree[L] {
  override def map[M](f: L=>M): BinarizedTree[M] = null; 
  def extend[B](f: BinarizedTree[L]=>B):BinarizedTree[B]
}

case class BinaryTree[+L](l: L,
                          leftChild: BinarizedTree[L],
                          rightChild: BinarizedTree[L])(span: Span
                        ) extends Tree[L](l,List(leftChild,rightChild))(span
                        ) with BinarizedTree[L] {
  override def map[M](f: L=>M):BinaryTree[M] = BinaryTree( f(label), leftChild map f, rightChild map f)(span);
  override def extend[B](f: BinarizedTree[L]=>B) = BinaryTree( f(this), leftChild extend f, rightChild extend f)(span);
}

case class UnaryTree[+L](l: L, child: BinarizedTree[L])(span: Span
                        ) extends Tree[L](l,List(child))(span
                        ) with BinarizedTree[L] {
  override def map[M](f: L=>M): UnaryTree[M] = UnaryTree( f(label), child map f)(span);
  override def extend[B](f: BinarizedTree[L]=>B) = UnaryTree( f(this), child extend f)(span);
}

case class NullaryTree[+L](l: L)(span: Span) extends Tree[L](l,Seq())(span) with BinarizedTree[L]{
  override def map[M](f: L=>M): NullaryTree[M] = NullaryTree( f(label))(span);
  override def extend[B](f: BinarizedTree[L]=>B) = NullaryTree( f(this))(span);
}

object Trees {
  def binarize[L](tree: Tree[L], relabel: (L,L)=>L):BinarizedTree[L] = tree match {
    case Tree(l, Seq()) => NullaryTree(l)(tree.span)
    case Tree(l, Seq(oneChild)) => UnaryTree(l,binarize(oneChild,relabel))(tree.span);
    case Tree(l, Seq(leftChild,rightChild)) => 
      BinaryTree(l,binarize(leftChild,relabel),binarize(rightChild,relabel))(tree.span);
    case Tree(l, Seq(leftChild, otherChildren@ _*)) =>
      val newLeftChild = binarize(leftChild,relabel);
      val newRightLabel = relabel(l,leftChild.label);
      val newRightChildSpan = Span(newLeftChild.span.end,tree.span.end);
      val newRightChild = binarize(Tree(newRightLabel,otherChildren)(newRightChildSpan), relabel);
      BinaryTree(l, newLeftChild, newRightChild)(tree.span) 
  }

  private def stringBinarizer(currentLabel: String, append: String) = { 
      val head = if(currentLabel(0) != '@') '@' + currentLabel + "->" else currentLabel
      head + "_" + append
  }
  def binarize(tree: Tree[String]):BinarizedTree[String] = binarize[String](tree, stringBinarizer _ );

  def debinarize[L](tree: Tree[L], isBinarized: L=>Boolean):Tree[L] = {
    val l = tree.label;
    val children = tree.children;
    val buf = new ArrayBuffer[Tree[L]];
    for(c <- children) {
      if(isBinarized(c.label)) {
        buf ++= debinarize(c,isBinarized).children;
      } else {
        buf += debinarize(c,isBinarized);
      }
    }
    Tree(l,buf)(tree.span);
  }

  def debinarize(tree: Tree[String]):Tree[String] = debinarize(tree, (x:String) => x.startsWith("@"));

  private def xbarStringBinarizer(currentLabel: String, append:String) = {
    if(currentLabel.startsWith("@")) currentLabel
    else "@" + currentLabel
  }
  def xBarBinarize(tree: Tree[String]) = binarize[String](tree,xbarStringBinarizer);

  object Transforms {
    class EmptyNodeStripper extends (Tree[String]=>Option[Tree[String]]) {
      def apply(tree: Tree[String]):Option[Tree[String]] = {
        if(tree.label == "-NONE-") None
        else if(tree.span.start == tree.span.end) None // screw stupid spans
        else {
          val newC = tree.children map this filter (None!=)
          if(newC.length == 0 && !tree.isLeaf) None
          else Some(Tree(tree.label,newC map (_.get))(tree.span))
        }
      }
    }
    class XOverXRemover[L] extends (Tree[L]=>Tree[L]) {
      def apply(tree: Tree[L]):Tree[L] = {
        if(tree.children.size == 1 && tree.label == tree.children(0).label) {
          this(tree.children(0));
        } else {
          Tree(tree.label,tree.children.map(this))(tree.span);
        }
      }
    }

    class FunctionNodeStripper extends (Tree[String]=>Tree[String]) {
      def apply(tree: Tree[String]): Tree[String] = {
        tree.map(_.replaceAll("-.+","")) 
      }
    }

    object StandardStringTransform extends (Tree[String]=>Tree[String]) {
      private val ens = new EmptyNodeStripper;
      private val xox = new XOverXRemover[String];
      private val fns = new FunctionNodeStripper;
      def apply(tree: Tree[String]): Tree[String] = {
        xox(fns(ens(tree).get)) map (_.intern);
      }
    }
  }
}