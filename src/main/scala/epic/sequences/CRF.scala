package epic.sequences

import breeze.util.Index
import breeze.numerics
import breeze.linalg.DenseVector
import epic.framework._
import java.util
import collection.mutable.ArrayBuffer
import breeze.features.FeatureVector
import epic.constraints.TagConstraints
import epic.util.CacheBroker
import breeze.optimize.FirstOrderMinimizer.OptParams

/**
 * A -Markov Linear Chain Conditional Random Field. Useful for POS tagging, etc.
 *
 * As usual in Epic, all the heavy lifting is done in the companion object and Marginals.
 *
 * CRFs can produce [[epic.sequences.TaggedSequence]] from an input sequence of words.
 * They can also produce marginals, etc.
 * @author dlwh
 */
@SerialVersionUID(1L)
trait CRF[L, W] extends Serializable {

  def labelIndex : Index[L]
  def startSymbol: L

  def anchor(w: IndexedSeq[W]):CRF.Anchoring[L, W]

  def marginal(w: IndexedSeq[W]) = {
     CRF.Marginal(anchor(w))
  }

  def goldMarginal(tags: IndexedSeq[L], w: IndexedSeq[W]):CRF.Marginal[L, W] = {
    CRF.Marginal.goldMarginal(anchor(w), tags)
  }

  def bestSequence(w: IndexedSeq[W], id: String = "") = {
    CRF.viterbi(anchor(w), id)
  }

  def lexicon: TagConstraints.Factory[L, W]

}

object CRF {

  /**
   * Builds a CRF from a corpus of TaggedSequences using reasonable defaults.
   * @param data
   * @param startSymbol
   * @param gazetteer
   * @param opt
   * @tparam L
   * @return
   */
  def buildSimple[L](data: IndexedSeq[TaggedSequence[L, String]],
                     startSymbol: L,
                     gazetteer: Gazetteer[Any, String] = Gazetteer.empty[Any, String],
                     opt: OptParams = OptParams())(implicit cache: CacheBroker = CacheBroker()):CRF[L, String] = {
    val model: CRFModel[L, String] = new TaggedSequenceModelFactory[L](startSymbol,  gazetteer = gazetteer).makeModel(data)


    /*
    val obj = new ModelObjective(model, data)

    val cached = new CachedBatchDiffFunction(obj)
    val weights = opt.minimize(cached, obj.initialWeightVector(randomize = false))
    */

    val percep = new StructSVM(new OneBestModelAdaptor(model))
    val weights = percep.train(data)
    val crf = model.extractCRF(weights)

    crf
  }

  def buildIOModel[L](data: IndexedSeq[TaggedSequence[L, String]],
                      outsideSymbol: L,
                      gazetteer: Gazetteer[Any, String] = Gazetteer.empty[Any, String],
                      opt: OptParams = OptParams()): CRF[Boolean, String] = {
    val fixedData: IndexedSeq[TaggedSequence[Boolean, String]] = data.map{s =>
      s.copy(tags=s.tags.map{l => (l != outsideSymbol)})
    }
    buildSimple(fixedData, false, gazetteer, opt)
  }


  trait Anchoring[L, W] {
    def words : IndexedSeq[W]
    def length: Int = words.length
    def scoreTransition(pos: Int, prev: Int, cur: Int):Double
    def labelIndex: Index[L]
    def startSymbol: L
    def validSymbols(pos: Int): Set[Int]
  }


  trait Marginal[L, W] extends VisitableMarginal[TransitionVisitor[L, W]] {

    def anchoring: Anchoring[L, W]
    def words: IndexedSeq[W] = anchoring.words
    def length: Int = anchoring.length
    /** Visits spans with non-zero score, useful for expected counts */
    def visit( f: TransitionVisitor[L, W])

    /** normalized probability of seeing segment with transition */
    def transitionMarginal(pos: Int, prev:Int, cur: Int):Double
    def logPartition: Double

    def positionMarginal(pos: Int, label: L):Double = positionMarginal(pos, anchoring.labelIndex(label))

    def positionMarginal(pos: Int):DenseVector[Double] = DenseVector.tabulate(anchoring.labelIndex.size)(positionMarginal(pos, _))

    def positionMarginal(pos: Int, label: Int):Double = {
      var prev = 0
      val numLabels: Int = anchoring.labelIndex.size
      var sum = 0.0
      while(prev <  numLabels) {
        sum += transitionMarginal(pos, prev, label)
        prev += 1
      }
      sum
    }
  }


  object Marginal {

    def apply[L, W](scorer: Anchoring[L, W]):Marginal[L, W] = {

      val forwardScores: Array[Array[Double]] = this.forwardScores(scorer)
      val backwardScore: Array[Array[Double]] = this.backwardScores(scorer)
      val partition = numerics.logSum(forwardScores.last)
      val _s = scorer


      new Marginal[L, W] {

        def anchoring: Anchoring[L, W] = _s

        def visit(f: TransitionVisitor[L, W]) {
          val numLabels = scorer.labelIndex.size
          var pos = 0
          while (pos < length) {
            var label = 0
            while (label < numLabels) {
              if(!backwardScore(pos+1)(label).isInfinite) {
                var prevLabel = 0
                while (prevLabel < numLabels) {
                  val score = transitionMarginal(pos, prevLabel, label)
                  if(score != 0.0)
                    f(pos, prevLabel, label,  score)
                  prevLabel += 1
                }
              }
              label += 1
            }
            pos += 1
          }

        }


        /** Log-normalized probability of seing segment with transition */
        def transitionMarginal(pos: Int, prev: Int, cur: Int): Double = {
          val withoutTrans = forwardScores(pos)(prev) + backwardScore(pos+1)(cur)
          if(withoutTrans.isInfinite) 0.0
          else math.exp(withoutTrans + anchoring.scoreTransition(pos, prev, cur) - logPartition)
        }



        def logPartition: Double = partition
//        println(words + " " + partition)
      }

    }



    def goldMarginal[L, W](scorer: Anchoring[L, W], tags: IndexedSeq[L]):Marginal[L, W] = {
      var lastSymbol = scorer.labelIndex(scorer.startSymbol)
      var score = 0.0
      for( (l, pos) <- tags.zipWithIndex) {
        val symbol = scorer.labelIndex(l)
        assert(symbol != -1, s"$l not in index: ${scorer.labelIndex}")
        score += scorer.scoreTransition(pos, lastSymbol, symbol)
        lastSymbol = symbol
      }

      val s = scorer

      new Marginal[L, W] {

        def anchoring: Anchoring[L, W] = s

        def visit(f: TransitionVisitor[L, W]) {
          var lastSymbol = scorer.labelIndex(scorer.startSymbol)
          for( (l,pos) <- tags.zipWithIndex) {
            val symbol = scorer.labelIndex(l)
            f.apply(pos, lastSymbol, symbol, 1.0)
            lastSymbol = symbol
          }

        }

        val indexedSymbols = scorer.labelIndex(scorer.startSymbol) +: tags.map(scorer.labelIndex(_))

        def transitionMarginal(pos: Int, prev: Int, cur: Int): Double = {
          numerics.I(prev == indexedSymbols(pos) && cur == indexedSymbols(pos + 1))
        }


        def logPartition: Double = score
      }
    }




    /**
     *
     * @param scorer
     * @return forwardScore(end position)(label) = forward score of ending a segment labeled label in position end position
     */
    private def forwardScores[L, W](scorer: CRF.Anchoring[L, W]): Array[Array[Double]] = {
      val length = scorer.length
      val numLabels = scorer.labelIndex.size
      // total weight (logSum) for reaching the fence post before position i with label l. i.e. forward(0) is the start state
      val forwardScores = Array.fill(length+1, numLabels)(Double.NegativeInfinity)
      forwardScores(0)(scorer.labelIndex(scorer.startSymbol)) = 0.0

      val cache = new Array[Double](numLabels * length)

      // forward
      for(i <- 0 until length) {
        val cur = forwardScores(i+1)
        for ( next <- scorer.validSymbols(i)) {
          var offset = 0
          for ( previous <- if(i == 0) IndexedSeq(scorer.labelIndex(scorer.startSymbol)) else scorer.validSymbols(i-1)) {
            val score = scorer.scoreTransition(i, previous, next) + forwardScores(i)(previous)
            if(score != Double.NegativeInfinity) {
              cache(offset) = score
              offset += 1
            }
          }
          cur(next) = numerics.logSum(cache,offset)
        }


      }


      forwardScores
    }

    /**
     * computes the sum of all completions of derivations, starting with label l at pos.
     * at the end of the sequence
     * @param scorer
     * @tparam L
     * @tparam W
     * @return backwardScore(pos)(label)
     */
    private def backwardScores[L, W](scorer: CRF.Anchoring[L, W]): Array[Array[Double]] = {
      val length = scorer.length
      val numLabels = scorer.labelIndex.size
      // total completion weight (logSum) for being in state l at fencepost i + 1,
      val backwardScores = Array.fill(length+1, numLabels)(Double.NegativeInfinity)
      util.Arrays.fill(backwardScores(length), 0.0)

      val accumArray = new Array[Double](numLabels)

      for(i <- (length-1) until 0 by -1) {
        val cur = backwardScores(i)
        for ( curLabel <- scorer.validSymbols(i-1)) {
          var offset = 0
          for( next <- scorer.validSymbols(i)) {
            val nextScore = backwardScores(i+1)(next)
            val score = scorer.scoreTransition(i, curLabel, next) + nextScore
            if(score != Double.NegativeInfinity) {
              accumArray(offset) = score
              offset += 1
            }
          }
          cur(curLabel) = numerics.logSum(accumArray,offset)

        }
      }

      backwardScores
    }



  }


  trait TransitionVisitor[L, W] {
    def apply(pos: Int, prev: Int, cur: Int, count: Double)
  }

  trait IndexedFeaturizer[L, W] {
    def anchor(w: IndexedSeq[W]):AnchoredFeaturizer[L, W]

    def startSymbol: L

    def labelIndex: Index[L]
    def featureIndex: Index[Feature]
  }

  trait AnchoredFeaturizer[L, W] {
    def featureIndex: Index[Feature]
    def featuresForTransition(pos: Int, prev: Int, cur: Int):FeatureVector
    def validSymbols(pos: Int):Set[Int]
  }


  def viterbi[L, W](scorer: Anchoring[L ,W], id: String=""):TaggedSequence[L, W] = {
    val length = scorer.length
    val numLabels = scorer.labelIndex.size
    // total weight (logSum) for reaching the fence post before position i with label l. i.e. forward(0) is the start state
    val forwardScores = Array.fill(length+1, numLabels)(Double.NegativeInfinity)
    forwardScores(0)(scorer.labelIndex(scorer.startSymbol)) = 0.0
    val backPointer = Array.fill(length, numLabels)(-1)


    // forward
    for(i <- 0 until length) {
      val cur = forwardScores(i+1)
      for ( next <- scorer.validSymbols(i)) {

        var currentMax = Double.NegativeInfinity
        var currentArgMax = -1

        for ( previous <- scorer.validSymbols(i-1)) {
          val score = scorer.scoreTransition(i, previous, next) + forwardScores(i)(previous)
          if(score > currentMax) {
            currentMax = score
            currentArgMax = previous
          }
        }
        assert(!currentMax.isNaN)
        assert(!currentMax.isInfinite)
        cur(next) = currentMax
        backPointer(i)(next) = currentArgMax
      }
    }
    val tags = ArrayBuffer[L]()
    def rec(end: Int, label: Int) {
      tags += scorer.labelIndex.get(label)
      if(end > 0) {
        val bestCurrentLabel = backPointer(end)(label)
        rec(end-1, bestCurrentLabel)
      }

    }
    rec(length-1, (0 until numLabels).maxBy(forwardScores(length)(_)))

    TaggedSequence(tags.reverse, scorer.words, id)
  }


  def posteriorDecode[L, W](m: Marginal[L, W], id: String = "") = {
    val length = m.length
    val labels = (0 until length).map(pos => DenseVector.tabulate(m.anchoring.labelIndex.size)(m.positionMarginal(pos, _)).argmax)

    TaggedSequence(labels.map(m.anchoring.labelIndex.get), m.words, id)
  }
}
