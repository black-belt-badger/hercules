{-# LANGUAGE GADTs #-}

-- Standalone reference: runghc experiments/haskell/FreeFreer.hs
-- Pass --emit to enable optional IO interpreter.
module Main (main) where

import Control.Monad (ap, unless, void, when)
import qualified Data.Map.Strict as Map
import System.Environment (getArgs)

type Env = Map.Map String String

-- GADT constructors choose an exact typed result per instruction.
data Instruction a where
  Query  :: String -> Instruction (Maybe String)
  Emit   :: String -> Instruction ()
  Update :: String -> String -> Instruction ()

-- Freer/operational encoding: Instruction does NOT need Functor.
data Freer a where
  PureR :: a -> Freer a
  BindR :: Instruction x -> (x -> Freer a) -> Freer a

instance Functor Freer where
  fmap f value = value >>= (pure . f)

instance Applicative Freer where
  pure = PureR
  (<*>) = ap

instance Monad Freer where
  PureR value >>= next = next value
  BindR op continuation >>= next =
    BindR op (\value -> continuation value >>= next)

queryR :: String -> Freer (Maybe String)
queryR key = BindR (Query key) PureR

emitR :: String -> Freer ()
emitR text = BindR (Emit text) PureR

updateR :: String -> String -> Freer ()
updateR key value = BindR (Update key value) PureR

-- Pure interpreter: immutable state threading and ordered events.
runFreer :: Freer a -> Env -> (a, Env, [String])
runFreer (PureR value) env = (value, env, [])
runFreer (BindR instruction next) env =
  case instruction of
    Query key ->
      runFreer (next (Map.lookup key env)) env
    Update key value ->
      runFreer (next ()) (Map.insert key value env)
    Emit message ->
      let (value, final, messages) = runFreer (next ()) env
       in (value, final, message : messages)

-- Optional IO interpreter, only explicitly invoked at a boundary.
runFreerIO :: Freer a -> Env -> IO (a, Env)
runFreerIO (PureR value) env = pure (value, env)
runFreerIO (BindR instruction next) env =
  case instruction of
    Query key ->
      runFreerIO (next (Map.lookup key env)) env
    Update key value ->
      runFreerIO (next ()) (Map.insert key value env)
    Emit message -> do
      putStrLn ("[explicit effect] " ++ message)
      runFreerIO (next ()) env

-- Classic higher-kinded Free, using a Functor signature layer.
data Layer next
  = QueryL String (Maybe String -> next)
  | EmitL String next
  | UpdateL String String next

instance Functor Layer where
  fmap f (QueryL key resume) = QueryL key (f . resume)
  fmap f (EmitL message next) = EmitL message (f next)
  fmap f (UpdateL key value next) = UpdateL key value (f next)

data Free f a
  = PureF a
  | Roll (f (Free f a))

instance Functor f => Functor (Free f) where
  fmap f program = program >>= (pure . f)

instance Functor f => Applicative (Free f) where
  pure = PureF
  (<*>) = ap

instance Functor f => Monad (Free f) where
  PureF value >>= next = next value
  Roll layer >>= next = Roll (fmap (>>= next) layer)

queryF :: String -> Free Layer (Maybe String)
queryF key = Roll (QueryL key PureF)

emitF :: String -> Free Layer ()
emitF message = Roll (EmitL message (PureF ()))

updateF :: String -> String -> Free Layer ()
updateF key value = Roll (UpdateL key value (PureF ()))

runClassic :: Free Layer a -> Env -> (a, Env, [String])
runClassic (PureF value) env = (value, env, [])
runClassic (Roll layer) env =
  case layer of
    QueryL key next ->
      runClassic (next (Map.lookup key env)) env
    UpdateL key value next ->
      runClassic next (Map.insert key value env)
    EmitL message next ->
      let (result, state, messages) = runClassic next env
       in (result, state, message : messages)

-- Same domain-neutral query/update/emit scenario in two encodings.
freerProgram :: Freer (Maybe String)
freerProgram = do
  found <- queryR "instrument"
  updateR "last-seen" (maybe "missing" id found)
  emitR "instrument.checked"
  queryR "last-seen"

classicProgram :: Free Layer (Maybe String)
classicProgram = do
  found <- queryF "instrument"
  updateF "last-seen" (maybe "missing" id found)
  emitF "instrument.checked"
  queryF "last-seen"

check :: String -> Bool -> IO ()
check title pass =
  unless pass (error ("FAILED: " ++ title))

main :: IO ()
main = do
  flags <- getArgs
  let initial = Map.singleton "instrument" "BTCUSD"
      expectedState = Map.fromList
        [("instrument", "BTCUSD"), ("last-seen", "BTCUSD")]
      expected = (Just "BTCUSD", expectedState, ["instrument.checked"])
      classic = runClassic classicProgram initial
      freer = runFreer freerProgram initial
  check "classic and freer pure interpreters agree" (classic == freer)
  check "expected query/update/emit result" (freer == expected)
  check "program reusable without mutable state" (runFreer freerProgram initial == freer)

  -- Monad laws by observable interpretation, not by syntax equality.
  let f n = do
        emitR "f"
        pure (n + 1)
      g n = do
        emitR "g"
        pure (n * 2)
      base = do
        emitR "base"
        pure (5 :: Int)
      run p = runFreer p Map.empty
  check "left identity" (run (pure 4 >>= f) == run (f 4))
  check "right identity" (run (base >>= pure) == run base)
  check "associativity" (run ((base >>= f) >>= g) ==
                         run (base >>= (\x -> f x >>= g)))
  check "event order" (let (_, _, events) = run (base >>= f >>= g)
                        in events == ["base", "f", "g"])

  putStrLn "PASS Haskell classic Free and GADT/Freer reference laws."
  when (elem "--emit" flags) $ void (runFreerIO freerProgram initial)
