from org.tatrman.common.v1 import response_message_pb2 as _response_message_pb2
from google.protobuf.internal import containers as _containers
from google.protobuf.internal import enum_type_wrapper as _enum_type_wrapper
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class NlpOp(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    NLP_OP_UNSPECIFIED: _ClassVar[NlpOp]
    TOKENIZE: _ClassVar[NlpOp]
    SENTENCE_SPLIT: _ClassVar[NlpOp]
    LEMMATIZE: _ClassVar[NlpOp]
    POS_TAG: _ClassVar[NlpOp]
    DEP_PARSE: _ClassVar[NlpOp]
    NER: _ClassVar[NlpOp]
    DETECT_LANGUAGE: _ClassVar[NlpOp]

class Mode(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    MODE_UNSPECIFIED: _ClassVar[Mode]
    NORMAL: _ClassVar[Mode]
    COMPARE: _ClassVar[Mode]

class Tier(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    TIER_UNSPECIFIED: _ClassVar[Tier]
    SELF_HOSTED_PINNED: _ClassVar[Tier]
    REMOTE_UNPINNED: _ClassVar[Tier]
NLP_OP_UNSPECIFIED: NlpOp
TOKENIZE: NlpOp
SENTENCE_SPLIT: NlpOp
LEMMATIZE: NlpOp
POS_TAG: NlpOp
DEP_PARSE: NlpOp
NER: NlpOp
DETECT_LANGUAGE: NlpOp
MODE_UNSPECIFIED: Mode
NORMAL: Mode
COMPARE: Mode
TIER_UNSPECIFIED: Tier
SELF_HOSTED_PINNED: Tier
REMOTE_UNPINNED: Tier

class Token(_message.Message):
    __slots__ = ("text", "char_start", "char_end", "lemma", "upos", "xpos", "feats", "dep_head", "dep_relation")
    class FeatsEntry(_message.Message):
        __slots__ = ("key", "value")
        KEY_FIELD_NUMBER: _ClassVar[int]
        VALUE_FIELD_NUMBER: _ClassVar[int]
        key: str
        value: str
        def __init__(self, key: _Optional[str] = ..., value: _Optional[str] = ...) -> None: ...
    TEXT_FIELD_NUMBER: _ClassVar[int]
    CHAR_START_FIELD_NUMBER: _ClassVar[int]
    CHAR_END_FIELD_NUMBER: _ClassVar[int]
    LEMMA_FIELD_NUMBER: _ClassVar[int]
    UPOS_FIELD_NUMBER: _ClassVar[int]
    XPOS_FIELD_NUMBER: _ClassVar[int]
    FEATS_FIELD_NUMBER: _ClassVar[int]
    DEP_HEAD_FIELD_NUMBER: _ClassVar[int]
    DEP_RELATION_FIELD_NUMBER: _ClassVar[int]
    text: str
    char_start: int
    char_end: int
    lemma: str
    upos: str
    xpos: str
    feats: _containers.ScalarMap[str, str]
    dep_head: int
    dep_relation: str
    def __init__(self, text: _Optional[str] = ..., char_start: _Optional[int] = ..., char_end: _Optional[int] = ..., lemma: _Optional[str] = ..., upos: _Optional[str] = ..., xpos: _Optional[str] = ..., feats: _Optional[_Mapping[str, str]] = ..., dep_head: _Optional[int] = ..., dep_relation: _Optional[str] = ...) -> None: ...

class Span(_message.Message):
    __slots__ = ("char_start", "char_end")
    CHAR_START_FIELD_NUMBER: _ClassVar[int]
    CHAR_END_FIELD_NUMBER: _ClassVar[int]
    char_start: int
    char_end: int
    def __init__(self, char_start: _Optional[int] = ..., char_end: _Optional[int] = ...) -> None: ...

class NerEntity(_message.Message):
    __slots__ = ("text", "label", "char_start", "char_end", "normalized_value", "source_engine")
    TEXT_FIELD_NUMBER: _ClassVar[int]
    LABEL_FIELD_NUMBER: _ClassVar[int]
    CHAR_START_FIELD_NUMBER: _ClassVar[int]
    CHAR_END_FIELD_NUMBER: _ClassVar[int]
    NORMALIZED_VALUE_FIELD_NUMBER: _ClassVar[int]
    SOURCE_ENGINE_FIELD_NUMBER: _ClassVar[int]
    text: str
    label: str
    char_start: int
    char_end: int
    normalized_value: str
    source_engine: str
    def __init__(self, text: _Optional[str] = ..., label: _Optional[str] = ..., char_start: _Optional[int] = ..., char_end: _Optional[int] = ..., normalized_value: _Optional[str] = ..., source_engine: _Optional[str] = ...) -> None: ...

class EngineResult(_message.Message):
    __slots__ = ("tokens", "entities", "sentences", "paragraphs", "error")
    TOKENS_FIELD_NUMBER: _ClassVar[int]
    ENTITIES_FIELD_NUMBER: _ClassVar[int]
    SENTENCES_FIELD_NUMBER: _ClassVar[int]
    PARAGRAPHS_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    tokens: _containers.RepeatedCompositeFieldContainer[Token]
    entities: _containers.RepeatedCompositeFieldContainer[NerEntity]
    sentences: _containers.RepeatedCompositeFieldContainer[Span]
    paragraphs: _containers.RepeatedCompositeFieldContainer[Span]
    error: str
    def __init__(self, tokens: _Optional[_Iterable[_Union[Token, _Mapping]]] = ..., entities: _Optional[_Iterable[_Union[NerEntity, _Mapping]]] = ..., sentences: _Optional[_Iterable[_Union[Span, _Mapping]]] = ..., paragraphs: _Optional[_Iterable[_Union[Span, _Mapping]]] = ..., error: _Optional[str] = ...) -> None: ...

class AnalyzeRequest(_message.Message):
    __slots__ = ("text", "language", "ops", "mode", "engine_hints")
    class EngineHintsEntry(_message.Message):
        __slots__ = ("key", "value")
        KEY_FIELD_NUMBER: _ClassVar[int]
        VALUE_FIELD_NUMBER: _ClassVar[int]
        key: str
        value: str
        def __init__(self, key: _Optional[str] = ..., value: _Optional[str] = ...) -> None: ...
    TEXT_FIELD_NUMBER: _ClassVar[int]
    LANGUAGE_FIELD_NUMBER: _ClassVar[int]
    OPS_FIELD_NUMBER: _ClassVar[int]
    MODE_FIELD_NUMBER: _ClassVar[int]
    ENGINE_HINTS_FIELD_NUMBER: _ClassVar[int]
    text: str
    language: str
    ops: _containers.RepeatedScalarFieldContainer[NlpOp]
    mode: Mode
    engine_hints: _containers.ScalarMap[str, str]
    def __init__(self, text: _Optional[str] = ..., language: _Optional[str] = ..., ops: _Optional[_Iterable[_Union[NlpOp, str]]] = ..., mode: _Optional[_Union[Mode, str]] = ..., engine_hints: _Optional[_Mapping[str, str]] = ...) -> None: ...

class AnalyzeResponse(_message.Message):
    __slots__ = ("language", "language_confidence", "engine_used", "tokens", "sentences", "paragraphs", "entities", "by_engine", "trace_id", "elapsed_ms", "detected_language", "used", "messages")
    class ByEngineEntry(_message.Message):
        __slots__ = ("key", "value")
        KEY_FIELD_NUMBER: _ClassVar[int]
        VALUE_FIELD_NUMBER: _ClassVar[int]
        key: str
        value: EngineResult
        def __init__(self, key: _Optional[str] = ..., value: _Optional[_Union[EngineResult, _Mapping]] = ...) -> None: ...
    LANGUAGE_FIELD_NUMBER: _ClassVar[int]
    LANGUAGE_CONFIDENCE_FIELD_NUMBER: _ClassVar[int]
    ENGINE_USED_FIELD_NUMBER: _ClassVar[int]
    TOKENS_FIELD_NUMBER: _ClassVar[int]
    SENTENCES_FIELD_NUMBER: _ClassVar[int]
    PARAGRAPHS_FIELD_NUMBER: _ClassVar[int]
    ENTITIES_FIELD_NUMBER: _ClassVar[int]
    BY_ENGINE_FIELD_NUMBER: _ClassVar[int]
    TRACE_ID_FIELD_NUMBER: _ClassVar[int]
    ELAPSED_MS_FIELD_NUMBER: _ClassVar[int]
    DETECTED_LANGUAGE_FIELD_NUMBER: _ClassVar[int]
    USED_FIELD_NUMBER: _ClassVar[int]
    MESSAGES_FIELD_NUMBER: _ClassVar[int]
    language: str
    language_confidence: float
    engine_used: str
    tokens: _containers.RepeatedCompositeFieldContainer[Token]
    sentences: _containers.RepeatedCompositeFieldContainer[Span]
    paragraphs: _containers.RepeatedCompositeFieldContainer[Span]
    entities: _containers.RepeatedCompositeFieldContainer[NerEntity]
    by_engine: _containers.MessageMap[str, EngineResult]
    trace_id: str
    elapsed_ms: int
    detected_language: str
    used: _containers.RepeatedCompositeFieldContainer[EngineVersion]
    messages: _containers.RepeatedCompositeFieldContainer[_response_message_pb2.ResponseMessage]
    def __init__(self, language: _Optional[str] = ..., language_confidence: _Optional[float] = ..., engine_used: _Optional[str] = ..., tokens: _Optional[_Iterable[_Union[Token, _Mapping]]] = ..., sentences: _Optional[_Iterable[_Union[Span, _Mapping]]] = ..., paragraphs: _Optional[_Iterable[_Union[Span, _Mapping]]] = ..., entities: _Optional[_Iterable[_Union[NerEntity, _Mapping]]] = ..., by_engine: _Optional[_Mapping[str, EngineResult]] = ..., trace_id: _Optional[str] = ..., elapsed_ms: _Optional[int] = ..., detected_language: _Optional[str] = ..., used: _Optional[_Iterable[_Union[EngineVersion, _Mapping]]] = ..., messages: _Optional[_Iterable[_Union[_response_message_pb2.ResponseMessage, _Mapping]]] = ...) -> None: ...

class EngineVersion(_message.Message):
    __slots__ = ("op", "engine", "model", "model_version")
    OP_FIELD_NUMBER: _ClassVar[int]
    ENGINE_FIELD_NUMBER: _ClassVar[int]
    MODEL_FIELD_NUMBER: _ClassVar[int]
    MODEL_VERSION_FIELD_NUMBER: _ClassVar[int]
    op: str
    engine: str
    model: str
    model_version: str
    def __init__(self, op: _Optional[str] = ..., engine: _Optional[str] = ..., model: _Optional[str] = ..., model_version: _Optional[str] = ...) -> None: ...

class BatchLemmatizeRequest(_message.Message):
    __slots__ = ("texts", "language")
    TEXTS_FIELD_NUMBER: _ClassVar[int]
    LANGUAGE_FIELD_NUMBER: _ClassVar[int]
    texts: _containers.RepeatedScalarFieldContainer[str]
    language: str
    def __init__(self, texts: _Optional[_Iterable[str]] = ..., language: _Optional[str] = ...) -> None: ...

class LemmaList(_message.Message):
    __slots__ = ("lemmas",)
    LEMMAS_FIELD_NUMBER: _ClassVar[int]
    lemmas: _containers.RepeatedScalarFieldContainer[str]
    def __init__(self, lemmas: _Optional[_Iterable[str]] = ...) -> None: ...

class BatchLemmatizeResponse(_message.Message):
    __slots__ = ("results", "used")
    RESULTS_FIELD_NUMBER: _ClassVar[int]
    USED_FIELD_NUMBER: _ClassVar[int]
    results: _containers.RepeatedCompositeFieldContainer[LemmaList]
    used: _containers.RepeatedCompositeFieldContainer[EngineVersion]
    def __init__(self, results: _Optional[_Iterable[_Union[LemmaList, _Mapping]]] = ..., used: _Optional[_Iterable[_Union[EngineVersion, _Mapping]]] = ...) -> None: ...

class StatusRequest(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class Capability(_message.Message):
    __slots__ = ("language", "op", "engine", "model_version", "tier")
    LANGUAGE_FIELD_NUMBER: _ClassVar[int]
    OP_FIELD_NUMBER: _ClassVar[int]
    ENGINE_FIELD_NUMBER: _ClassVar[int]
    MODEL_VERSION_FIELD_NUMBER: _ClassVar[int]
    TIER_FIELD_NUMBER: _ClassVar[int]
    language: str
    op: NlpOp
    engine: str
    model_version: str
    tier: Tier
    def __init__(self, language: _Optional[str] = ..., op: _Optional[_Union[NlpOp, str]] = ..., engine: _Optional[str] = ..., model_version: _Optional[str] = ..., tier: _Optional[_Union[Tier, str]] = ...) -> None: ...

class StatusResponse(_message.Message):
    __slots__ = ("ready", "capabilities")
    READY_FIELD_NUMBER: _ClassVar[int]
    CAPABILITIES_FIELD_NUMBER: _ClassVar[int]
    ready: bool
    capabilities: _containers.RepeatedCompositeFieldContainer[Capability]
    def __init__(self, ready: _Optional[bool] = ..., capabilities: _Optional[_Iterable[_Union[Capability, _Mapping]]] = ...) -> None: ...
